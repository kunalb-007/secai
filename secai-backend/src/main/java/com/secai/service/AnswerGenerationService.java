package com.secai.service;

import com.secai.domain.document.DocumentChunk;
import com.secai.domain.document.DocumentChunkRepository;
import com.secai.domain.questionnaire.*;
import com.secai.domain.questionnaire.AiGenerationJob.AiJobStatus;
import com.secai.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AnswerGenerationService {

    // ── Retrieval config ──────────────────────────────────────────────────────

    // With small corpora (6–30 chunks covering many topics), a single chunk
// covers multiple security domains. The cosine distance between a question
// about "MFA" and a chunk covering auth + passwords + MFA is typically
// 0.38–0.50 — comfortably over the old 0.35 cutoff.
// The hybrid reranker (FTS branch + combined scoring) handles false positives.
    private static final double VECTOR_THRESHOLD = 0.55;

    /**
     * RAISED from 5 → 7.
     *
     * Rationale: multi-part security questions often need evidence from
     * different sections (e.g. "Do you have MFA and access reviews?" spans
     * two sections). Top-5 was sometimes missing the second piece of evidence.
     * Top-7 with 800-char context cap ≈ 1,400 tokens — within GPT-4o-mini limits.
     */
    private static final int TOP_K = 7;

    // 300 tokens × ~4.5 chars/token = ~1350 chars.
// Set to 1500 to never truncate a standard chunk while still bounding
// runaway chunks from the paragraph-fallback path.
    private static final int MAX_CONTEXT_CHARS = 1500;

//    // ── Confidence penalties ──────────────────────────────────────────────────
//    private static final double PENALTY_FEW_CHUNKS     = 0.7;
//    private static final double PENALTY_SOME_CHUNKS    = 0.9;
//    private static final double LOW_CONFIDENCE_CEILING = 0.15;

    // Maximum confidence allowed when the LLM explicitly says there is
// insufficient evidence in the uploaded documentation.
    private static final double LOW_CONFIDENCE_CEILING = 0.45;

    private final QuestionRepository          questionRepo;
    private final AiGenerationJobRepository   jobRepo;
    private final QuestionnaireRepository     questionnaireRepo;
    private final DocumentChunkRepository     chunkRepo;
    private final EmbeddingService            embeddingService;
    private final LlmService                  llmService;
    private final AnswerGenerationAsyncRunner asyncRunner;
    private final TransactionTemplate         txTemplate;

    public AnswerGenerationService(
            QuestionRepository          questionRepo,
            AiGenerationJobRepository   jobRepo,
            QuestionnaireRepository     questionnaireRepo,
            DocumentChunkRepository     chunkRepo,
            EmbeddingService            embeddingService,
            LlmService                  llmService,
            @Lazy AnswerGenerationAsyncRunner asyncRunner,
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
        if (total == 0) throw new IllegalStateException("No questions found.");

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

    public void generateAnswer(Question question, UUID orgId) {

        // ── Step 1: Build normalised query ────────────────────────────────────
        // Expand synonyms BEFORE embedding so the vector is semantically broader.
        // E.g. "Is MFA enforced?" → "Is MFA enforced? multi-factor authentication
        //      required enabled mandatory" — the extra terms pull the embedding
        // centroid toward the dense auth/access-control region of the vector space.
        String normalizedQuestion = normalizeQuery(question.getQuestionText());

        // ── Step 2: Embed the normalised question ─────────────────────────────
        float[] embedding    = embeddingService.embed(normalizedQuestion);
        String  embeddingStr = embeddingService.toVectorString(embedding);

        // ── Step 3: Build FTS keyword string ─────────────────────────────────
        // Extract the core keywords for plainto_tsquery.
        // We use the ORIGINAL question (not the expanded one) for FTS because
        // the extra synonym terms would generate too many false FTS matches.
        String keywords = extractKeywords(question.getQuestionText());

        // ── Step 4: Hybrid search ─────────────────────────────────────────────
        List<DocumentChunk> chunks = chunkRepo.findHybrid(
                orgId, embeddingStr, keywords, VECTOR_THRESHOLD, TOP_K
        );

        log.info("[question:{}] Hybrid search returned {} chunks (keywords: '{}')",
                question.getId(), chunks.size(), keywords);

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk c = chunks.get(i);

            log.info("""
        Rank {}
        Doc={}
        Section={}
        Similarity={}
        Text={}
        """,
                    i + 1,
                    c.getDocumentId(),
                    c.getSectionTitle(),
                    c.getSimilarity(),
                    c.getText().substring(0, Math.min(120, c.getText().length()))
            );
        }

        // ── Step 5: Build context ─────────────────────────────────────────────
        String context = buildContext(chunks);

        // ── Step 6: LLM call ─────────────────────────────────────────────────
        // Use the ORIGINAL question in the prompt — the LLM needs the real question,
        // not the synonym-expanded version.
        String llmResponse = llmService.complete(
                buildSystemPrompt(),
                buildUserMessage(context, question.getQuestionText())
        );

        // ── Step 7: Parse response ────────────────────────────────────────────
        String answer     = extractAnswer(llmResponse);
        String evidence   = extractEvidence(llmResponse);
        double confidence = computeConfidence(chunks, answer);

        UUID sourceChunkId    = chunks.isEmpty() ? null : chunks.get(0).getId();
        UUID sourceDocumentId = chunks.isEmpty() ? null : chunks.get(0).getDocumentId();

        // ── Step 8: Save in a short-lived transaction ─────────────────────────
        final String finalAnswer      = answer.trim();
        final String finalEvidence    = evidence.trim();
        final double finalConfidence  = confidence;
        final UUID   finalChunkId     = sourceChunkId;
        final UUID   finalDocumentId  = sourceDocumentId;

        txTemplate.execute(tx -> {
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

        log.info("[question:{}] Saved. confidence={} sourceChunk={} chunks={}",
                question.getId(),
                String.format("%.2f", confidence),
                String.format("%.2f", chunks.isEmpty() ? 0.0 : chunks.get(0).getSimilarity()),
                chunks.size());
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

    // ── Query normalisation ───────────────────────────────────────────────────

    /**
     * Expands security questionnaire questions with synonyms before embedding.
     *
     * WHY THIS IS NEEDED:
     * Security questionnaires use highly variable phrasing for identical concepts.
     * "Is MFA enforced?" and "Do you require multi-factor authentication?" are
     * semantically identical but their embeddings can be 0.30–0.45 cosine distance
     * apart — enough to miss retrieval at a 0.35 threshold.
     *
     * HOW IT WORKS:
     * We append a synonym expansion string after the original question.
     * The embedding model averages across the full input, pulling the vector
     * toward the centroid of all the synonym meanings.
     * The LLM receives only the ORIGINAL question (see buildUserMessage).
     *
     * SYNONYM GROUPS (tuned for common security questionnaire patterns):
     * Each group maps one "question word" to the set of equivalent terms
     * used in security documentation (policies, SOC 2 reports, etc.).
     */
    private String normalizeQuery(String questionText) {
        if (questionText == null || questionText.isBlank()) return questionText;

        String lower = questionText.toLowerCase();
        List<String> expansions = new ArrayList<>();

        // ── Authentication / Access control ───────────────────────────────────
        if (matches(lower, "mfa", "multi-factor", "two-factor", "2fa", "2-factor")) {
            expansions.add("multi-factor authentication MFA two-factor authentication 2FA required mandatory enabled");
        }
        if (matches(lower, "sso", "single sign-on", "identity provider", "idp", "saml", "oauth", "oidc")) {
            expansions.add("single sign-on SSO identity provider IdP SAML OAuth OIDC authentication");
        }
        if (matches(lower, "password", "passphrase", "credential")) {
            expansions.add("password passphrase credential minimum length complexity policy");
        }
        if (matches(lower, "access review", "access recertification", "user review", "periodic review", "quarterly review", "annual review")) {
            expansions.add("access review recertification user access rights periodic quarterly annual review revoke");
        }
        if (matches(lower, "privileged", "admin", "superuser", "root access")) {
            expansions.add("privileged access administrator superuser root service account just-in-time PAM");
        }
        if (matches(lower, "rbac", "role-based", "least privilege", "need to know")) {
            expansions.add("role-based access control RBAC least privilege need-to-know permissions");
        }

        // ── Encryption ────────────────────────────────────────────────────────
        if (matches(lower, "encrypt", "encryption", "cipher", "aes", "tls", "ssl")) {
            expansions.add("encryption AES-256 TLS 1.2 TLS 1.3 SSL cipher data protection in transit at rest");
        }
        if (matches(lower, "key management", "key rotation", "kms", "hsm")) {
            expansions.add("encryption key management rotation KMS HSM key lifecycle AWS KMS");
        }
        if (matches(lower, "tls", "ssl", "https", "in transit", "transport")) {
            expansions.add("TLS 1.2 TLS 1.3 SSL HTTPS transport encryption in transit mutual TLS");
        }
        if (matches(lower, "data at rest", "disk encryption", "storage encryption", "database encryption")) {
            expansions.add("data at rest encryption disk AES-256 encrypted database storage volumes");
        }

        // ── Logging / Monitoring ──────────────────────────────────────────────
        if (matches(lower, "log", "logging", "audit trail", "audit log", "event log")) {
            expansions.add("logging audit trail log retention SIEM event monitoring centralized");
        }
        if (matches(lower, "log retention", "retain log", "how long", "retention period")) {
            expansions.add("log retention period days months years audit trail storage compliance");
        }
        if (matches(lower, "alert", "alerting", "notification", "failed login", "intrusion")) {
            expansions.add("alerting notification SIEM anomaly detection failed login brute force intrusion");
        }
        if (matches(lower, "siem", "security information", "event management", "splunk", "elk")) {
            expansions.add("SIEM security information event management log aggregation Splunk ELK monitoring");
        }

        // ── Vulnerability / Penetration testing ───────────────────────────────
        if (matches(lower, "penetration test", "pentest", "pen test", "red team")) {
            expansions.add("penetration testing pentest annual third-party red team vulnerability assessment scope");
        }
        if (matches(lower, "vulnerability", "cve", "scan", "patch", "remediat")) {
            expansions.add("vulnerability management scanning patching CVE CVSS remediation SLA critical high");
        }
        if (matches(lower, "sast", "dast", "static analysis", "dynamic analysis", "code scan")) {
            expansions.add("SAST DAST static dynamic code analysis security scanning pipeline CI/CD");
        }

        // ── Backup / Recovery ─────────────────────────────────────────────────
        if (matches(lower, "backup", "back up", "restore", "recovery")) {
            expansions.add("backup recovery restore encrypted offsite retention tested RTO RPO");
        }
        if (matches(lower, "rto", "recovery time objective", "rpo", "recovery point objective")) {
            expansions.add("RTO recovery time objective RPO recovery point objective SLA availability disaster");
        }
        if (matches(lower, "disaster recovery", "business continuity", "bcp", "drp")) {
            expansions.add("disaster recovery business continuity plan BCP DRP failover resilience");
        }

        // ── Infrastructure / Cloud ────────────────────────────────────────────
        if (matches(lower, "cloud provider", "cloud hosting", "aws", "azure", "gcp", "google cloud")) {
            expansions.add("cloud provider AWS Azure GCP Amazon Web Services hosting infrastructure region");
        }
        if (matches(lower, "secret", "api key", "credentials", "vault", "secret management")) {
            expansions.add("secret management API key credentials vault HashiCorp rotation environment variable");
        }

        // ── SDLC / Development ────────────────────────────────────────────────
        if (matches(lower, "pull request", "code review", "peer review", "merge", "branch")) {
            expansions.add("pull request code review peer review merge branch approval SDLC");
        }

        // ── Physical security ─────────────────────────────────────────────────
        if (matches(lower, "visitor", "physical access", "data center", "badge", "cctv")) {
            expansions.add("visitor registration physical access data center badge CCTV camera log escort");
        }

        // ── Compliance / Risk ─────────────────────────────────────────────────
        if (matches(lower, "compliance", "framework", "iso 27001", "soc 2", "nist", "gdpr", "hipaa", "pci")) {
            expansions.add("compliance framework ISO 27001 SOC 2 NIST GDPR HIPAA PCI-DSS certification audit");
        }
        if (matches(lower, "risk assessment", "risk management", "risk register")) {
            expansions.add("risk assessment risk management risk register annual threat model impact likelihood");
        }

        // ── Training / HR ─────────────────────────────────────────────────────
        if (matches(lower, "security training", "security awareness", "phishing", "annual training")) {
            expansions.add("security awareness training annual phishing simulation onboarding policy acknowledgement");
        }
        if (matches(lower, "vendor", "third party", "supplier", "sub-processor", "fourth party")) {
            expansions.add("vendor third-party supplier risk assessment annual review contract DPA sub-processor");
        }

        // ── Time period synonyms (catches "annually" vs "yearly" etc.) ────────
        if (matches(lower, "annual", "annually", "yearly", "once a year", "per year")) {
            expansions.add("annual annually yearly once a year periodic frequency");
        }
        if (matches(lower, "quarter", "quarterly", "every 3 months", "periodically")) {
            expansions.add("quarterly periodically regular schedule frequency review");
        }

        // ── Boolean / enforcement synonyms ────────────────────────────────────
        if (matches(lower, "enforced", "required", "mandatory", "must", "compulsory")) {
            expansions.add("enforced required mandatory enabled configured policy required");
        }
        if (matches(lower, "enabled", "active", "in place", "implemented", "deployed")) {
            expansions.add("enabled active implemented deployed configured in place enforced");
        }

        if (expansions.isEmpty()) {
            return questionText; // no expansion needed
        }

        // Append expansions as a separate line so the embedding model
        // treats them as supplementary context, not part of the question.
        return questionText + "\n" + String.join(" ", expansions);
    }

    /**
     * Returns true if the text contains ANY of the given terms (case-insensitive).
     */
    private boolean matches(String lowerText, String... terms) {
        for (String term : terms) {
            if (lowerText.contains(term.toLowerCase())) return true;
        }
        return false;
    }

    // ── Keyword extraction for FTS ────────────────────────────────────────────

    /**
     * Extracts keywords from the ORIGINAL (non-expanded) question for FTS.
     *
     * plainto_tsquery handles stop words, stemming, and tokenization automatically.
     * We just need to pass a clean string. The full question works well.
     * Cap at 200 chars to avoid passing absurdly long strings to the FTS parser.
     */
    private String extractKeywords(String questionText) {
        if (questionText == null || questionText.isBlank()) return "security";

        // Keep alphanumeric, spaces, hyphens, dots, slashes, and colons.
        // Slashes and colons appear in security terms: "AES-256/GCM", "TLS 1.3", "SHA-256".
        // The downstream sanitizer in findHybrid() removes anything that breaks plainto_tsquery.
        String cleaned = questionText
                .replaceAll("[^a-zA-Z0-9\\s\\-./:]", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        return cleaned.length() > 250 ? cleaned.substring(0, 250) : cleaned;
    }

    // ── Context builder ───────────────────────────────────────────────────────

    private String trimChunk(String text) {
        if (text == null) return "";
        return text.length() <= MAX_CONTEXT_CHARS ? text : text.substring(0, MAX_CONTEXT_CHARS);
    }

    // AFTER
    private String buildContext(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return "No relevant documentation found.";
        // Include rank so the LLM can weight the most relevant chunk higher.
        // Only include chunks above a minimum combined_score to avoid injecting
        // noise from the bottom of the TOP_K list when the corpus is small.
        List<DocumentChunk> usable = chunks.stream()
                .filter(c -> c.getSimilarity() >= 0.15)
                .toList();
        if (usable.isEmpty()) usable = chunks.subList(0, 1); // always keep at least one

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < usable.size(); i++) {
            DocumentChunk c = usable.get(i);
            if (i > 0) sb.append("\n\n---\n\n");
            sb.append(String.format("[Source %d | %s]\n%s",
                    i + 1,
                    c.getSectionTitle() != null ? c.getSectionTitle() : "Document",
                    trimChunk(c.getText())));
        }
        return sb.toString();
    }

    // ── Prompts ───────────────────────────────────────────────────────────────

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

    // ── Response parsing ──────────────────────────────────────────────────────

    private String extractAnswer(String response) {
        if (response == null || response.isBlank()) return "Unable to generate answer.";
        String n = response.replace("\r\n", "\n").replace("\r", "\n");
        int ai = n.toLowerCase().indexOf("answer:");
        int ei = n.toLowerCase().indexOf("evidence:");
        if (ai == -1) return n.trim();
        int start = ai + "answer:".length();
        if (ei > ai) return n.substring(start, ei).strip();
        return n.substring(start).strip();
    }

    private String extractEvidence(String response) {
        if (response == null || response.isBlank()) return "N/A";
        String n = response.replace("\r\n", "\n").replace("\r", "\n");
        int idx = n.toLowerCase().indexOf("evidence:");
        if (idx == -1) return "N/A";
        String e = n.substring(idx + "evidence:".length()).strip();
        int nl = e.indexOf('\n');
        if (nl > 0) e = e.substring(0, nl).strip();
        return e.isBlank() ? "N/A" : e;
    }

    // ── Confidence scoring ────────────────────────────────────────────────────

    // AFTER
    private double computeConfidence(List<DocumentChunk> chunks, String answer) {

        // No supporting evidence retrieved at all.
        if (chunks.isEmpty()) {
            return 0.05;
        }

        // --- Base score from top-chunk retrieval ---
        // combined_score = semantic(0.7) + FTS(0.3), range [0, 1].
        // FTS-only chunks land around 0.15–0.30 even when they contain the exact answer,
        // so we use relaxed tiers that treat anything above 0.20 as potentially useful.
        double topScore = chunks.get(0).getSimilarity();

        double baseConfidence;
        if (topScore >= 0.70) {
            baseConfidence = 0.92;
        } else if (topScore >= 0.55) {
            baseConfidence = 0.82;
        } else if (topScore >= 0.38) {
            baseConfidence = 0.70;
        } else if (topScore >= 0.22) {
            baseConfidence = 0.58;
        } else if (topScore >= 0.10) {
            baseConfidence = 0.42;
        } else {
            baseConfidence = 0.25;
        }

        // --- Corroboration bonus: multiple chunks agreeing raises confidence ---
        // Each additional chunk above a minimum threshold adds a small bonus,
        // capped so that 3+ supporting chunks can lift a borderline score to "high".
        long corroboratingChunks = chunks.stream()
                .skip(1) // skip top chunk already accounted for
                .filter(c -> c.getSimilarity() >= 0.18)
                .count();
        double corroborationBonus = Math.min(corroboratingChunks * 0.05, 0.15);

        double confidence = baseConfidence + corroborationBonus;

        // AFTER
// Only penalise when the LLM's answer is purely negative — i.e. the
// entire answer signals missing evidence. Partial answers that begin
// with a hedge but contain substantive content should not be capped.
// Strategy: check for the exact sentinel the system prompt mandates
// ("Insufficient evidence in current documentation") and a small set
// of unambiguous no-evidence phrases.
        if (answer != null) {
            String lower = answer.toLowerCase().trim();
            boolean purelyNegative =
                    lower.startsWith("insufficient evidence")
                            || lower.equals("no evidence found")
                            || (lower.contains("not documented") && lower.length() < 80)
                            || (lower.contains("no information") && lower.length() < 80);
            if (purelyNegative) {
                confidence = Math.min(confidence, LOW_CONFIDENCE_CEILING);
            }
        }

        return Math.max(0.0, Math.min(1.0, confidence));
    }
}