package com.secai.service;

import com.secai.domain.document.DocumentChunk;
import com.secai.domain.document.DocumentChunkRepository;
import com.secai.domain.questionnaire.*;
import com.secai.domain.questionnaire.AiGenerationJob.AiJobStatus;
import com.secai.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AnswerGenerationService {

    // ── Retrieval config ──────────────────────────────────────────────────────
    /**
     * Cosine distance threshold for vector search.
     * 0.35 distance ≈ 0.65 cosine similarity — deliberately loose so hybrid
     * scoring can surface keyword-match chunks even with lower semantic score.
     */
    private static final double VECTOR_THRESHOLD  = 0.35;

    /**
     * Retrieve top 5 candidates (up from 3).
     * Rationale: multi-part security questions (encryption + key management +
     * data retention) need evidence from different sections. Top-3 missed one.
     * Top-5 with 800 chars each = ~1000 tokens context — well within GPT-4o-mini limits.
     */
    private static final int TOP_K = 5;

    private static final int MAX_CONTEXT_CHARS = 800;

    // ── Confidence penalties ──────────────────────────────────────────────────
    private static final double PENALTY_FEW_CHUNKS     = 0.7;
    private static final double PENALTY_SOME_CHUNKS    = 0.9;
    private static final double LOW_CONFIDENCE_CEILING = 0.15;

    private final QuestionRepository           questionRepo;
    private final AiGenerationJobRepository    jobRepo;
    private final QuestionnaireRepository      questionnaireRepo;
    private final DocumentChunkRepository      chunkRepo;
    private final EmbeddingService             embeddingService;
    private final LlmService                   llmService;
    private final AnswerGenerationAsyncRunner  asyncRunner;
    /**
     * Used for the short save-only transaction in generateAnswer().
     * Replaces the broad @Transactional that was holding a DB connection
     * open during 1-3 second LLM API calls.
     */
    private final TransactionTemplate          txTemplate;

    public AnswerGenerationService(
            QuestionRepository          questionRepo,
            AiGenerationJobRepository   jobRepo,
            QuestionnaireRepository     questionnaireRepo,
            DocumentChunkRepository     chunkRepo,
            EmbeddingService            embeddingService,
            LlmService                  llmService,
            AnswerGenerationAsyncRunner asyncRunner,
            TransactionTemplate         txTemplate
    ) {
        this.questionRepo      = questionRepo;
        this.jobRepo           = jobRepo;
        this.questionnaireRepo = questionnaireRepo;
        this.chunkRepo         = chunkRepo;
        this.embeddingService  = embeddingService;
        this.llmService        = llmService;
        this.asyncRunner       = asyncRunner;
        this.txTemplate        = txTemplate;
    }

    // ── Public trigger ────────────────────────────────────────────────────────

    @Transactional
    public AiGenerationJob startGeneration(UUID questionnaireId, UUID orgId) {
        Questionnaire questionnaire = questionnaireRepo
                .findByIdAndOrganizationId(questionnaireId, orgId)
                .orElseThrow(() -> new NotFoundException("Questionnaire not found"));

        AiGenerationJob job = jobRepo.findByQuestionnaireId(questionnaireId)
                .orElseThrow(() -> new NotFoundException(
                        "No AI generation job found. Re-upload the questionnaire."));

        if (job.getStatus() == AiJobStatus.RUNNING) {
            log.info("[{}] Generation already RUNNING", questionnaireId);
            return job;
        }
        if (job.getStatus() == AiJobStatus.COMPLETED) {
            log.info("[{}] Generation already COMPLETED", questionnaireId);
            return job;
        }

        long total = questionRepo.countByQuestionnaireIdAndOrganizationId(questionnaireId, orgId);
        if (total == 0) {
            throw new IllegalStateException("No questions found in this questionnaire.");
        }

        job.setStatus(AiJobStatus.RUNNING);
        job.setTotalQuestions((int) total);
        job.setCompletedQuestions(0);
        job.setStartedAt(OffsetDateTime.now());
        job.setFinishedAt(null);
        jobRepo.save(job);

        questionnaire.setStatus(QuestionnaireStatus.GENERATING);
        questionnaireRepo.save(questionnaire);

        log.info("[{}] Dispatching async generation for {} questions", questionnaireId, total);
        asyncRunner.runAsync(job.getId(), questionnaireId, orgId);

        return job;
    }

    // ── Per-question RAG pipeline ─────────────────────────────────────────────

    /**
     * Full RAG pipeline for one question.
     *
     * Transaction boundary fix: @Transactional is intentionally REMOVED.
     * The old approach held a DB connection open for the full duration including:
     *   - OpenAI embedding call (~200ms)
     *   - pgvector search (~10ms)
     *   - OpenAI LLM call (~1-3s)
     * With 400 questions and HikariCP's default pool of 10, this caused
     * connection pool exhaustion. Now only the final save opens a transaction.
     */
    public void generateAnswer(Question question, UUID orgId) {
        // ── Step 1: Embed question (no DB connection held) ────────────────────
        float[] embedding     = embeddingService.embed(question.getQuestionText());
        String  embeddingStr  = embeddingService.toVectorString(embedding);

        // Extract keywords from the question for FTS.
        // plainto_tsquery handles multi-word phrases safely.
        String keywords = extractKeywords(question.getQuestionText());

        // ── Step 2: Hybrid search (vector + keyword) ──────────────────────────
        List<DocumentChunk> chunks = chunkRepo.findHybrid(
                orgId, embeddingStr, keywords, VECTOR_THRESHOLD, TOP_K
        );

        log.info("[question:{}] Hybrid search returned {} chunks (keywords: '{}')",
                question.getId(), chunks.size(), keywords);

        // ── Step 3: Build context (no DB connection held) ─────────────────────
        String context = buildContext(chunks);

        // ── Step 4: LLM call (no DB connection held) ──────────────────────────
        String llmResponse = llmService.complete(buildSystemPrompt(), buildUserMessage(context, question.getQuestionText()));

        // ── Step 5: Parse response (no DB connection held) ───────────────────
        String answer   = extractAnswer(llmResponse);
        String evidence = extractEvidence(llmResponse);
        double confidence = computeConfidence(chunks, answer);

        // ── Step 6: Identify best source chunk for "View Source" ──────────────
        // The first chunk in the list has the highest combined score.
        UUID sourceChunkId    = chunks.isEmpty() ? null : chunks.get(0).getId();
        UUID sourceDocumentId = chunks.isEmpty() ? null : chunks.get(0).getDocumentId();

        // ── Step 7: Short-lived transaction only for the DB write ─────────────
        final String  finalAnswer        = answer.trim();
        final String  finalEvidence      = evidence.trim();
        final double  finalConfidence    = confidence;
        final UUID    finalChunkId       = sourceChunkId;
        final UUID    finalDocumentId    = sourceDocumentId;

        txTemplate.execute(tx -> {
            // Re-fetch inside transaction to avoid stale state
            Question q = questionRepo.findById(question.getId()).orElse(null);
            if (q == null) return null;

            q.setAiAnswer(finalAnswer);
            q.setEvidence(finalEvidence);
            q.setRetrievalScore(finalConfidence);
            q.setStatus(QuestionStatus.GENERATED);
            q.setSourceChunkId(finalChunkId);
            q.setSourceDocumentId(finalDocumentId);
            return questionRepo.save(q);
        });

        log.info("[question:{}] Saved. confidence={:.2f} sourceChunk={}",
                question.getId(), confidence, sourceChunkId);
    }

    @Transactional
    public void markQuestionFailed(Question question) {
        question.setAiAnswer("Answer generation failed for this question. Please enter manually.");
        question.setEvidence("N/A");
        question.setRetrievalScore(0.0);
        question.setSourceChunkId(null);
        question.setSourceDocumentId(null);
        question.setStatus(QuestionStatus.GENERATED);
        questionRepo.save(question);
    }

    // ── Context builder ───────────────────────────────────────────────────────

    private String trimChunk(String text) {
        if (text == null) return "";
        return text.length() <= MAX_CONTEXT_CHARS ? text : text.substring(0, MAX_CONTEXT_CHARS);
    }

    private String buildContext(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return "No relevant documentation found.";
        return chunks.stream()
                .map(c -> String.format("[%s]\n%s",
                        c.getSectionTitle() != null ? c.getSectionTitle() : "Document",
                        trimChunk(c.getText())))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    // ── Prompt: few-shot system prompt ────────────────────────────────────────

    /**
     * Few-shot system prompt with 2 worked examples.
     *
     * Why few-shot:
     * - Zero-shot answers often add preamble ("Based on the context provided...")
     *   which wastes tokens and sounds like a chatbot, not a security professional.
     * - The examples teach the LLM the exact compliance voice customers expect.
     * - Both examples cover the two main answer patterns: Yes/confirmed and procedural.
     *
     * Token cost: ~180 tokens per question vs ~80 before = ~100 extra tokens.
     * At 400 questions: 40,000 extra input tokens ≈ $0.006 at GPT-4o-mini pricing.
     * Negligible cost for meaningfully better output quality.
     */
    private String buildSystemPrompt() {
        return """
You answer security questionnaires using ONLY the provided context. Never use outside knowledge.

Examples:

Context: [Encryption Policy]
All customer data at rest is encrypted using AES-256-GCM. Encryption keys are managed via AWS KMS with annual rotation.

Question: Do you encrypt data at rest?
Answer: Yes. Customer data at rest is encrypted using AES-256-GCM, with keys managed via AWS KMS and rotated annually.
Evidence: Encryption Policy

---

Context: [Incident Response Plan - Section 3]
Security incidents are classified as P1 (critical), P2 (high), or P3 (medium). P1 incidents must be reported to the Security Manager within 1 hour and resolved within 4 hours per the SLA matrix.

Question: Describe your incident response procedure.
Answer: Incidents are classified P1-P3 by severity. P1 incidents are reported to the Security Manager within 1 hour and resolved within 4 hours per the defined SLA matrix.
Evidence: Incident Response Plan - Section 3

---

Rules:
1. If the context lacks sufficient evidence, respond exactly:
   Answer: Insufficient evidence in current documentation.
   Evidence: N/A
2. Never write "the context states", "according to the document", or similar phrases.
3. Be direct and factual. Use compliance language. Maximum 2 sentences.
4. Always use this exact format — no other text before or after.
""";
    }

    private String buildUserMessage(String context, String questionText) {
        return "Context:\n" + context + "\n\nQuestion: " + questionText;
    }

    // ── Keyword extraction for FTS ─────────────────────────────────────────────

    /**
     * Extracts meaningful keywords from the question for PostgreSQL plainto_tsquery.
     *
     * plainto_tsquery handles the input safely — it tokenizes, stems, and
     * removes stop words. We just need to pass meaningful terms, not a perfect query.
     *
     * We preserve the full question text since plainto_tsquery will do the right thing.
     * Short trimming ensures we don't pass absurdly long strings.
     */
    private String extractKeywords(String questionText) {
        if (questionText == null || questionText.isBlank()) return "security";
        // plainto_tsquery handles full sentences well — just cap the length
        return questionText.length() > 200
                ? questionText.substring(0, 200)
                : questionText;
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private String extractAnswer(String response) {
        if (response == null || response.isBlank()) return "Unable to generate answer.";
        String normalized = response.replace("\r\n", "\n").replace("\r", "\n");
        int answerIdx   = normalized.toLowerCase().indexOf("answer:");
        int evidenceIdx = normalized.toLowerCase().indexOf("evidence:");
        if (answerIdx == -1) return normalized.trim();
        int start = answerIdx + "answer:".length();
        if (evidenceIdx > answerIdx) return normalized.substring(start, evidenceIdx).strip();
        return normalized.substring(start).strip();
    }

    private String extractEvidence(String response) {
        if (response == null || response.isBlank()) return "N/A";
        String normalized = response.replace("\r\n", "\n").replace("\r", "\n");
        int idx = normalized.toLowerCase().indexOf("evidence:");
        if (idx == -1) return "N/A";
        String evidence = normalized.substring(idx + "evidence:".length()).strip();
        int newline = evidence.indexOf('\n');
        if (newline > 0) evidence = evidence.substring(0, newline).strip();
        return evidence.isBlank() ? "N/A" : evidence;
    }

    // ── Confidence scoring ────────────────────────────────────────────────────

    /**
     * Retrieval-based confidence. Uses the combined hybrid score stored as
     * getSimilarity() on the top chunk (similarity = 1.0 - distance,
     * where distance = 1.0 - combined_score from the hybrid query).
     */
    private double computeConfidence(List<DocumentChunk> chunks, String answer) {
        if (chunks.isEmpty()) return 0.05;
        double confidence = chunks.get(0).getSimilarity();  // = combined hybrid score
        if (chunks.size() < 2) confidence *= PENALTY_FEW_CHUNKS;
        else if (chunks.size() < 3) confidence *= PENALTY_SOME_CHUNKS;
        if (answer != null) {
            String lower = answer.toLowerCase();
            if (lower.contains("insufficient") || lower.contains("not mentioned")
                    || lower.contains("no evidence") || lower.contains("not found")
                    || lower.contains("unable to")) {
                confidence = Math.min(confidence, LOW_CONFIDENCE_CEILING);
            }
        }
        return Math.clamp(confidence, 0.0, 1.0);
    }
}