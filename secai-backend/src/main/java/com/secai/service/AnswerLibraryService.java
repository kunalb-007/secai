package com.secai.service;

import com.secai.config.TenantContext;
import com.secai.domain.library.ApprovedAnswer;
import com.secai.domain.library.ApprovedAnswerRepository;
import com.secai.domain.questionnaire.Question;
import com.secai.domain.questionnaire.QuestionRepository;
import com.secai.domain.questionnaire.QuestionStatus;
import com.secai.dto.library.ApprovedAnswerMatch;
import com.secai.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Approved Answer Library — stores and retrieves approved answers for reuse.
 *
 * Write path (called from QuestionReviewService.approve()):
 *   1. Embed the approved question text
 *   2. Upsert into approved_answer table (update if source_question_id already exists)
 *
 * Read path (called from AnswerLibraryController):
 *   1. Embed the incoming question text
 *   2. Vector search the library (org-scoped, threshold 0.18 ≈ similarity > 0.82)
 *   3. Return top match (or empty)
 *
 * Threshold rationale:
 *   0.18 cosine distance ≈ 0.82 cosine similarity.
 *   This is deliberately higher than document retrieval (0.25) because
 *   we only want to surface library matches that are genuinely the same question
 *   phrased differently — not merely topically related.
 *   "Do you encrypt data at rest?" and "Is data encrypted at rest?" → ~0.94 similarity ✓
 *   "Do you encrypt data at rest?" and "What is your key rotation policy?" → ~0.61 similarity ✗
 */
@Service
public class AnswerLibraryService {

    private static final Logger log = LoggerFactory.getLogger(AnswerLibraryService.class);

    /** Cosine distance threshold for library match (lower = stricter) */
    private static final double MATCH_THRESHOLD = 0.18;   // ≈ similarity > 0.82
    private static final int    TOP_K           = 1;       // return best match only

    private final ApprovedAnswerRepository libraryRepo;
    private final QuestionRepository       questionRepo;
    private final EmbeddingService         embeddingService;

    public AnswerLibraryService(
            ApprovedAnswerRepository libraryRepo,
            QuestionRepository       questionRepo,
            EmbeddingService         embeddingService
    ) {
        this.libraryRepo      = libraryRepo;
        this.questionRepo     = questionRepo;
        this.embeddingService = embeddingService;
    }

    // ── Write: index an approved answer ──────────────────────────────────────

    /**
     * Called @Async from QuestionReviewService after a question is approved.
     * Runs on the doc-processor thread pool so it never blocks the HTTP response.
     *
     * @param questionId      the question that was just approved
     * @param approvedByEmail the reviewer's email (from JWT / TenantContext at call time)
     * @param orgId           the tenant (captured at call time, before TenantContext is cleared)
     */
    @Async
    public void indexApprovedAnswer(UUID questionId, String approvedByEmail, UUID orgId) {
        try {
            Question q = questionRepo.findById(questionId).orElse(null);
            if (q == null) {
                log.warn("[library] Question {} not found — skipping index", questionId);
                return;
            }

            // Determine the canonical answer text to store
            String answerText = resolveAnswerText(q);
            if (answerText == null || answerText.isBlank()) {
                log.debug("[library] Question {} has no answer — skipping index", questionId);
                return;
            }

            // Embed the question text
            float[] embedding      = embeddingService.embed(q.getQuestionText());
            String  embeddingStr   = embeddingService.toVectorString(embedding);

            // Upsert: if this question was already indexed (re-approve), update it
            Optional<ApprovedAnswer> existing =
                    libraryRepo.findBySourceQuestionId(questionId);

            if (existing.isPresent()) {
                ApprovedAnswer entry = existing.get();
                entry.setAnswerText(answerText);
                entry.setEvidence(q.getEvidence());
                entry.setApprovedByEmail(approvedByEmail);
                entry.setApprovedAt(OffsetDateTime.now());
                entry.setQuestionEmbedding(embedding);
                libraryRepo.save(entry);
                log.debug("[library] Updated entry for question {}", questionId);
            } else {
                libraryRepo.save(
                        ApprovedAnswer.builder()
                                .organizationId(orgId)
                                .sourceQuestionId(questionId)
                                .sourceQuestionText(q.getQuestionText())
                                .answerText(answerText)
                                .evidence(q.getEvidence())
                                .approvedByEmail(approvedByEmail)
                                .approvedAt(OffsetDateTime.now())
                                .questionEmbedding(embedding)
                                .build()
                );
                log.debug("[library] Indexed new entry for question {}", questionId);
            }

        } catch (Exception e) {
            // Non-fatal — a library index failure should never bubble up to the reviewer
            log.error("[library] Failed to index question {}: {}", questionId, e.getMessage(), e);
        }
    }

    // ── Read: find matching library entry ─────────────────────────────────────

    /**
     * Searches the library for a question similar to the given question.
     * Returns the best match above MATCH_THRESHOLD, or empty.
     *
     * @param questionId the question to find a library match for
     */
    @Transactional(readOnly = true)
    public Optional<ApprovedAnswerMatch> findMatch(UUID questionId) {
        UUID orgId = TenantContext.get();

        Question question = questionRepo.findById(questionId)
                .orElseThrow(() -> new NotFoundException("Question not found: " + questionId));

        // Don't suggest library matches for questions that are already approved
        if (question.getStatus() == QuestionStatus.APPROVED
                || question.getStatus() == QuestionStatus.EDITED) {
            return Optional.empty();
        }

        // Only search if the org has library entries
        if (libraryRepo.countByOrganizationId(orgId) == 0) {
            return Optional.empty();
        }

        // Embed the question
        float[] embedding = embeddingService.embed(question.getQuestionText());
        String  embStr    = embeddingService.toVectorString(embedding);

        // Search the library
        List<Object[]> rows = libraryRepo.findSimilarRaw(
                orgId, embStr, MATCH_THRESHOLD, TOP_K
        );

        if (rows.isEmpty()) return Optional.empty();

        // Map the single best result
        Object[] row   = rows.get(0);
        ApprovedAnswer match = mapRow(row);

        log.debug("[library] Found match for question {} — similarity {:.0f}%",
                questionId, match.getSimilarity() * 100);

        return Optional.of(ApprovedAnswerMatch.from(match));
    }

    // ── Apply: reuse a library answer ─────────────────────────────────────────

    /**
     * Called when the reviewer clicks "Reuse this answer".
     * Sets the question's manual_answer to the library answer and status → APPROVED.
     * Also triggers re-indexing of this entry (it's now approved too).
     *
     * @param questionId     the question to apply the library answer to
     * @param libraryEntryId the approved_answer entry to pull the answer from
     */
    @Transactional
    public void reuseAnswer(UUID questionId, UUID libraryEntryId) {
        UUID orgId = TenantContext.get();

        Question question = questionRepo.findById(questionId)
                .orElseThrow(() -> new NotFoundException("Question not found"));

        if (!orgId.equals(question.getOrganizationId())) {
            throw new com.secai.exception.ForbiddenException("Access denied");
        }

        ApprovedAnswer library = libraryRepo.findById(libraryEntryId)
                .orElseThrow(() -> new NotFoundException("Library entry not found"));

        if (!orgId.equals(library.getOrganizationId())) {
            throw new com.secai.exception.ForbiddenException("Access denied to library entry");
        }

        // Apply the library answer
        question.setManualAnswer(library.getAnswerText());
        question.setEvidence(library.getEvidence());
        question.setStatus(QuestionStatus.APPROVED);
        questionRepo.save(question);

        log.info("[library] Reused library entry {} for question {}", libraryEntryId, questionId);
    }

    // ── Library stats ─────────────────────────────────────────────────────────

    /** Total approved answers indexed for the current org */
    @Transactional(readOnly = true)
    public long getLibrarySize() {
        return libraryRepo.countByOrganizationId(TenantContext.get());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Determines the best answer text to store in the library.
     * For EDITED questions the manual_answer is the human-verified text.
     * For APPROVED/GENERATED questions the ai_answer is used.
     */
    private String resolveAnswerText(Question q) {
        if (q.getManualAnswer() != null && !q.getManualAnswer().isBlank()) {
            return q.getManualAnswer();
        }
        return q.getAiAnswer();
    }

    /**
     * Maps a native Object[] row from findSimilarRaw() into an ApprovedAnswer
     * with the distance field set (for getSimilarity() to work).
     *
     * Column order matches the SELECT in findSimilarRaw():
     * 0=id, 1=org_id, 2=src_q_id, 3=src_q_text, 4=answer,
     * 5=evidence, 6=email, 7=approved_at, 8=distance
     */
    private ApprovedAnswer mapRow(Object[] row) {
        ApprovedAnswer a = new ApprovedAnswer();
        a.setId(row[0] != null ? UUID.fromString(row[0].toString()) : null);
        a.setOrganizationId(row[1] != null ? UUID.fromString(row[1].toString()) : null);
        a.setSourceQuestionId(row[2] != null ? UUID.fromString(row[2].toString()) : null);
        a.setSourceQuestionText(row[3] != null ? row[3].toString() : "");
        a.setAnswerText(row[4] != null ? row[4].toString() : "");
        a.setEvidence(row[5] != null ? row[5].toString() : null);
        a.setApprovedByEmail(row[6] != null ? row[6].toString() : null);
        if (row[7] != null) {
            // approved_at comes back as java.sql.Timestamp from native query
            a.setApprovedAt(((java.sql.Timestamp) row[7]).toInstant()
                    .atOffset(java.time.ZoneOffset.UTC));
        }
        if (row[8] != null) {
            a.setDistance(((Number) row[8]).doubleValue());
        }
        return a;
    }
}