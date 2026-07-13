package com.secai.service;

import com.secai.domain.document.DocumentChunk;
import com.secai.domain.document.DocumentChunkRepository;
import com.secai.domain.questionnaire.*;
import com.secai.domain.questionnaire.AiGenerationJob.AiJobStatus;
import com.secai.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 5 — AI Investigation Engine.
 *
 * Orchestrates RAG-based answer generation for every question in a questionnaire:
 *  1. Embed each question using text-embedding-3-small
 *  2. Vector-search the org's document_chunk table (top-5, cosine similarity > 0.75)
 *  3. Build a context block from retrieved chunks
 *  4. Call gpt-4o-mini with the prompt template
 *  5. Parse Answer / Evidence from the structured response
 *  6. Compute retrieval-based confidence (no LLM self-reporting)
 *  7. Persist results and update job progress
 *
 * The generation runs @Async so the HTTP request returns immediately.
 * Progress is polled by the frontend via GET /api/questionnaires/{id}/job.
 */
@Service
public class AnswerGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerationService.class);

    // Cosine *distance* threshold — pgvector uses distance (lower = more similar).
    // 0.25 distance ≈ 0.75 cosine similarity
    private static final double SIMILARITY_THRESHOLD = 0.25;
    private static final int    TOP_K               = 5;

    // Confidence penalties applied on top of raw retrieval score
    private static final double PENALTY_FEW_CHUNKS    = 0.7;   // < 2 chunks retrieved
    private static final double PENALTY_SOME_CHUNKS   = 0.9;   // < 3 chunks
    private static final double LOW_CONFIDENCE_CEILING = 0.15; // "insufficient evidence" answers

    private final QuestionRepository         questionRepo;
    private final AiGenerationJobRepository  jobRepo;
    private final QuestionnaireRepository    questionnaireRepo;
    private final DocumentChunkRepository    chunkRepo;
    private final EmbeddingService           embeddingService;
    private final LlmService                 llmService;

    public AnswerGenerationService(
            QuestionRepository        questionRepo,
            AiGenerationJobRepository jobRepo,
            QuestionnaireRepository   questionnaireRepo,
            DocumentChunkRepository   chunkRepo,
            EmbeddingService          embeddingService,
            LlmService                llmService
    ) {
        this.questionRepo      = questionRepo;
        this.jobRepo           = jobRepo;
        this.questionnaireRepo = questionnaireRepo;
        this.chunkRepo         = chunkRepo;
        this.embeddingService  = embeddingService;
        this.llmService        = llmService;
    }

    // ── Public trigger (called from controller) ──────────────────────────────

    /**
     * Starts the async answer generation pipeline for a questionnaire.
     * Validates ownership and that a job exists, then dispatches async work.
     *
     * @param questionnaireId the questionnaire to answer
     * @param orgId           the tenant (from JWT / TenantContext)
     * @return the AiGenerationJob that was started
     */
    @Transactional
    public AiGenerationJob startGeneration(UUID questionnaireId, UUID orgId) {
        // Verify the questionnaire belongs to this org
        Questionnaire questionnaire = questionnaireRepo
                .findByIdAndOrganizationId(questionnaireId, orgId)
                .orElseThrow(() -> new NotFoundException("Questionnaire not found"));

        // Fetch or create the generation job
        AiGenerationJob job = jobRepo.findByQuestionnaireId(questionnaireId)
                .orElseThrow(() -> new NotFoundException(
                        "No AI generation job found for this questionnaire. "
                                + "Re-upload the questionnaire to create one."
                ));

        // Idempotency guard — don't re-run if already running/completed
        if (job.getStatus() == AiJobStatus.RUNNING) {
            log.info("[{}] Generation already RUNNING, ignoring duplicate trigger", questionnaireId);
            return job;
        }
        if (job.getStatus() == AiJobStatus.COMPLETED) {
            log.info("[{}] Generation already COMPLETED", questionnaireId);
            return job;
        }

        // Count questions to answer
        long total = questionRepo
                .countByQuestionnaireIdAndOrganizationId(questionnaireId, orgId);

        if (total == 0) {
            throw new IllegalStateException("No questions found in this questionnaire.");
        }

        // Step 1: Mark job RUNNING
        job.setStatus(AiJobStatus.RUNNING);
        job.setTotalQuestions((int) total);
        job.setCompletedQuestions(0);
        job.setStartedAt(OffsetDateTime.now());
        job.setFinishedAt(null);
        jobRepo.save(job);

        // Update questionnaire status
        questionnaire.setStatus(QuestionnaireStatus.GENERATING);
        questionnaireRepo.save(questionnaire);

        log.info("[{}] Starting AI generation for {} questions", questionnaireId, total);

        // Dispatch async — this method returns immediately to the caller
        runGenerationAsync(job.getId(), questionnaireId, orgId);

        return job;
    }

    // ── Async pipeline ───────────────────────────────────────────────────────

    /**
     * The actual pipeline — runs on the doc-processor thread pool.
     * Processes questions in order, one at a time, updating job progress after each.
     *
     * Isolation: if one question fails, we log and continue rather than aborting
     * the entire job — a single bad question should not block 399 others.
     */
    @Async
    public void runGenerationAsync(UUID jobId, UUID questionnaireId, UUID orgId) {
        log.info("[job:{}] Async generation started on thread: {}",
                jobId, Thread.currentThread().getName());

        AiGenerationJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) {
            log.error("[job:{}] Job not found — aborting", jobId);
            return;
        }

        try {
            // Load all questions for this questionnaire (ordered by sort_order)
            List<com.secai.domain.questionnaire.Question> questions =
                    questionRepo.findByQuestionnaireIdAndOrganizationIdOrderBySortOrder(
                            questionnaireId, orgId
                    );

            int completed = 0;

            for (com.secai.domain.questionnaire.Question question : questions) {
                try {
                    generateAnswer(question, orgId);
                    completed++;
                } catch (Exception e) {
                    // Log but continue — don't let one bad question kill the job
                    log.error("[job:{}] Failed to answer question {}: {}",
                            jobId, question.getId(), e.getMessage());
                    markQuestionFailed(question);
                    completed++;
                }

                // Step 3: Update job progress after each question
                job.setCompletedQuestions(completed);
                jobRepo.save(job);
            }

            // All done — mark COMPLETED
            job.setStatus(AiJobStatus.COMPLETED);
            job.setFinishedAt(OffsetDateTime.now());
            job.setCompletedQuestions(completed);
            jobRepo.save(job);

            // Update questionnaire status
            questionnaireRepo.findById(questionnaireId).ifPresent(q -> {
                q.setStatus(QuestionnaireStatus.COMPLETED);
                questionnaireRepo.save(q);
            });

            log.info("[job:{}] Generation COMPLETED. {} / {} questions answered.",
                    jobId, completed, questions.size());

        } catch (Exception e) {
            log.error("[job:{}] Fatal error in generation pipeline: {}", jobId, e.getMessage(), e);
            job.setStatus(AiJobStatus.FAILED);
            job.setFinishedAt(OffsetDateTime.now());
            jobRepo.save(job);

            questionnaireRepo.findById(questionnaireId).ifPresent(q -> {
                q.setStatus(QuestionnaireStatus.FAILED);
                questionnaireRepo.save(q);
            });
        }
    }

    // ── Per-question RAG pipeline ─────────────────────────────────────────────

    /**
     * Step 2 — Full RAG pipeline for one question.
     * This is a @Transactional method so the question update is atomic.
     */
    @Transactional
    public void generateAnswer(com.secai.domain.questionnaire.Question question, UUID orgId) {
        log.debug("Answering question {}: {}", question.getId(),
                question.getQuestionText().substring(0, Math.min(60, question.getQuestionText().length())));

        // 1. Embed the question text
        float[] questionEmbedding = embeddingService.embed(question.getQuestionText());
        String  embeddingString   = embeddingService.toVectorString(questionEmbedding);

        // 2. Vector search — org-scoped, top-5 chunks within similarity threshold
        List<DocumentChunk> chunks = chunkRepo.findSimilar(
                orgId,
                embeddingString,
                SIMILARITY_THRESHOLD,
                TOP_K
        );

        // 3. Build context from retrieved chunks
        String context = buildContext(chunks);

        // 4. Construct the prompt
        String systemPrompt = buildSystemPrompt();
        String userMessage  = buildUserMessage(context, question.getQuestionText());

        // 5. Call the LLM
        String llmResponse = llmService.complete(systemPrompt, userMessage);

        // 6. Parse structured response
        String answer   = extractAnswer(llmResponse);
        String evidence = extractEvidence(llmResponse);

        // 7. Compute confidence from retrieval similarity (not LLM self-report)
        double confidence = computeConfidence(chunks, answer);

        // 8. Persist
        question.setAiAnswer(answer.trim());
        question.setEvidence(evidence.trim());
        question.setRetrievalScore(confidence);
        question.setStatus(QuestionStatus.GENERATED);
        questionRepo.save(question);
    }

    // ── Context builder ───────────────────────────────────────────────────────

    private String buildContext(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return "No relevant documentation found.";
        }
        return chunks.stream()
                .map(c -> String.format("[%s]\n%s",
                        c.getSectionTitle() != null ? c.getSectionTitle() : "Document",
                        c.getText()))
                .collect(Collectors.joining("\n\n"));
    }

    // ── Prompt templates ──────────────────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
            You are a security compliance expert helping complete security questionnaires.
            You answer questions using ONLY the provided document context.
            You are factual, concise, and cite your sources precisely.
            Never invent information not present in the context.
            If the context does not contain enough information, say so clearly.
            """;
    }

    private String buildUserMessage(String context, String questionText) {
        return String.format("""
            Answer the security question using ONLY the context below.
            Be concise (1-3 sentences). Cite the section title where the answer was found.
            
            Context:
            %s
            
            Question: %s
            
            Format your response EXACTLY as:
            Answer: [your answer here]
            Evidence: [section title where you found this]
            
            If the context does not contain relevant information, respond:
            Answer: Insufficient evidence in current documentation to answer this question.
            Evidence: N/A
            """,
                context, questionText
        );
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    /**
     * Extracts the text between "Answer:" and "Evidence:" (or end of string).
     * Falls back to the full response if the format is unexpected.
     */
    private String extractAnswer(String response) {
        if (response == null || response.isBlank()) {
            return "Unable to generate answer.";
        }

        // Normalize line endings
        String normalized = response.replace("\r\n", "\n").replace("\r", "\n");

        int answerIdx   = normalized.toLowerCase().indexOf("answer:");
        int evidenceIdx = normalized.toLowerCase().indexOf("evidence:");

        if (answerIdx == -1) {
            // Model didn't follow format — return trimmed response as-is
            return normalized.trim();
        }

        int answerStart = answerIdx + "answer:".length();

        if (evidenceIdx > answerIdx) {
            return normalized.substring(answerStart, evidenceIdx).strip();
        } else {
            return normalized.substring(answerStart).strip();
        }
    }

    /**
     * Extracts the text after "Evidence:".
     */
    private String extractEvidence(String response) {
        if (response == null || response.isBlank()) return "N/A";

        String normalized = response.replace("\r\n", "\n").replace("\r", "\n");
        int idx = normalized.toLowerCase().indexOf("evidence:");

        if (idx == -1) return "N/A";

        String evidence = normalized.substring(idx + "evidence:".length()).strip();

        // Take only the first line if multi-line
        int newline = evidence.indexOf('\n');
        if (newline > 0) {
            evidence = evidence.substring(0, newline).strip();
        }

        return evidence.isBlank() ? "N/A" : evidence;
    }

    // ── Confidence calculation ────────────────────────────────────────────────

    /**
     * Computes retrieval-based confidence score (0.0 – 1.0).
     *
     * Algorithm from the MVP plan:
     *  - Base: top chunk similarity (1 - cosine_distance)
     *  - Penalise if fewer than 2 or 3 chunks returned (weak signal)
     *  - Cap at LOW_CONFIDENCE_CEILING if the answer indicates insufficient evidence
     *
     * Note: pgvector returns distance, not similarity. We convert:
     *   similarity = 1.0 - distance
     * The chunk's getSimilarity() method does this conversion (see DocumentChunk).
     */
    private double computeConfidence(List<DocumentChunk> chunks, String answer) {
        if (chunks.isEmpty()) return 0.05;

        // Base confidence from top chunk's cosine similarity
        double topSimilarity = chunks.get(0).getSimilarity();
        double confidence    = topSimilarity;

        // Penalise sparse retrieval
        if (chunks.size() < 2) confidence *= PENALTY_FEW_CHUNKS;
        else if (chunks.size() < 3) confidence *= PENALTY_SOME_CHUNKS;

        // Cap if the answer itself signals low confidence
        if (answer != null) {
            String lower = answer.toLowerCase();
            if (lower.contains("insufficient") || lower.contains("not mentioned")
                    || lower.contains("no evidence") || lower.contains("not found")
                    || lower.contains("unable to")) {
                confidence = Math.min(confidence, LOW_CONFIDENCE_CEILING);
            }
        }

        return Math.min(Math.max(confidence, 0.0), 1.0);
    }

    // ── Failure handling ──────────────────────────────────────────────────────

    /**
     * Marks a question as having failed to generate (sets a placeholder answer
     * and low confidence so it shows as red in the review UI).
     */
    @Transactional
    public void markQuestionFailed(com.secai.domain.questionnaire.Question question) {
        question.setAiAnswer("Answer generation failed for this question. Please enter manually.");
        question.setEvidence("N/A");
        question.setRetrievalScore(0.0);
        question.setStatus(QuestionStatus.GENERATED);
        questionRepo.save(question);
    }
}