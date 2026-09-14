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

    private static final double VECTOR_THRESHOLD    = 0.55;
    private static final int    TOP_K               = 7;
    private static final int    MAX_CONTEXT_CHARS   = 1500;
    private static final double LOW_CONFIDENCE_CEILING = 0.45;

    // Two chunks are considered textually duplicate if their first N chars match.
    // 200 chars covers one or two sentences — enough to detect overlapping chunks
    // whose sectionTitles differ slightly (e.g. null vs "A&A-01").
    private static final int DUPLICATE_TEXT_PREFIX_LEN = 200;

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

        // ── Step 1: Normalise query ───────────────────────────────────────────
        String normalizedQuestion = normalizeQuery(question.getQuestionText());

        // ── Step 2: Embed ─────────────────────────────────────────────────────
        float[] embedding    = embeddingService.embed(normalizedQuestion);
        String  embeddingStr = embeddingService.toVectorString(embedding);

        // ── Step 3: FTS keywords ──────────────────────────────────────────────
        String keywords = extractKeywords(question.getQuestionText());

        // ── Step 4: Hybrid search — raw results ──────────────────────────────
        List<DocumentChunk> rawChunks = chunkRepo.findHybrid(
                orgId, embeddingStr, keywords, VECTOR_THRESHOLD, TOP_K
        );

        // ── Step 5: Deduplicate for context only ──────────────────────────────
        // rawChunks is kept intact for confidence scoring (see computeConfidence).
        // deduplicatedChunks is used only for building the LLM prompt, so the LLM
        // never sees the same section twice and doesn't repeat it in Evidence.
        List<DocumentChunk> deduplicatedChunks = deduplicateChunks(rawChunks);

        log.info("[question:{}] Hybrid search: {} raw → {} deduplicated (keywords: '{}')",
                question.getId(), rawChunks.size(), deduplicatedChunks.size(), keywords);

        // ── Step 6: Filter chunks for LLM context (same logic as buildContext) ──
        List<DocumentChunk> contextChunks = deduplicatedChunks.stream()
                .filter(c -> c.getSimilarity() >= 0.45)
                .collect(Collectors.toList());
        if (contextChunks.isEmpty() && !deduplicatedChunks.isEmpty()) {
            contextChunks = deduplicatedChunks.subList(0, 1);
        }
        if (contextChunks.size() > 3) {
            contextChunks = contextChunks.subList(0, 3);
        }

        // ── Step 7: Build context from filtered chunks only ────────────────────
        String context = buildContext(contextChunks);

        // ── Step 8: LLM call ──────────────────────────────────────────────────
        String llmResponse = llmService.complete(
                buildSystemPrompt(),
                buildUserMessage(context, question.getQuestionText())
        );

        // ── Step 9: Parse response ────────────────────────────────────────────
        String answer   = extractAnswer(llmResponse);
        // Pass contextChunks (not deduplicatedChunks) so evidence dedup only
        // considers the sections the LLM actually saw
        String evidence = extractEvidence(llmResponse, contextChunks);
        // Pass rawChunks so corroboration bonus uses all matching chunks
        double confidence = computeConfidence(rawChunks, answer);

        // sourceChunkId comes from the top context chunk (highest scoring filtered chunk)
        UUID sourceChunkId    = contextChunks.isEmpty() ? null : contextChunks.get(0).getId();
        UUID sourceDocumentId = contextChunks.isEmpty() ? null : contextChunks.get(0).getDocumentId();

        // ── Step 10: Save ─────────────────────────────────────────────────────
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

        log.info("[question:{}] Saved. confidence={} topRawSimilarity={} rawChunks={} dedupChunks={} contextChunks={}",
                question.getId(),
                String.format("%.2f", confidence),
                String.format("%.2f", rawChunks.isEmpty() ? 0.0 : rawChunks.get(0).getSimilarity()),
                rawChunks.size(),
                deduplicatedChunks.size(),
                contextChunks.size());
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

    // ── Chunk deduplication (for context only) ────────────────────────────────

    /**
     * Removes duplicate chunks before building the LLM prompt.
     *
     * Two passes of deduplication:
     *
     * Pass 1 — by (documentId, sectionTitle):
     *   The chunker's 50-token overlap means chunks 51, 52, 53 from section "A&A-01"
     *   all score well and all enter the prompt as [A&A-01], [A&A-01], [A&A-01].
     *   The LLM then writes "Evidence: A&A-01, A&A-01, A&A-01".
     *   Keep only the highest-scoring chunk per (documentId, sectionTitle).
     *
     * Pass 2 — by text prefix:
     *   Catches chunks whose sectionTitle differs slightly (e.g. null vs "A&A-01",
     *   or "A&A-01" vs "A&A-01 Authentication") but whose actual text content is
     *   almost identical (overlapping text windows). If the first 200 chars match,
     *   the chunks are textually the same — keep the higher scorer.
     *
     * IMPORTANT: this list is used ONLY for building the context string and for
     * extractEvidence(). computeConfidence() still receives rawChunks so that the
     * corroboration bonus (multiple chunks supporting the same answer) is preserved.
     */
    private List<DocumentChunk> deduplicateChunks(List<DocumentChunk> chunks) {
        if (chunks.size() <= 1) return chunks;

        // Pass 1: deduplicate by (documentId + sectionTitle)
        Map<String, DocumentChunk> bySectionKey = new LinkedHashMap<>();
        for (DocumentChunk chunk : chunks) {
            String key = chunk.getDocumentId()
                    + ":"
                    + (chunk.getSectionTitle() != null ? chunk.getSectionTitle() : "");
            bySectionKey.merge(key, chunk, (existing, candidate) ->
                    candidate.getSimilarity() > existing.getSimilarity() ? candidate : existing
            );
        }

        // Re-sort after merge (merge can disorder by similarity)
        List<DocumentChunk> pass1 = bySectionKey.values().stream()
                .sorted(Comparator.comparingDouble(DocumentChunk::getSimilarity).reversed())
                .collect(Collectors.toList());

        if (pass1.size() <= 1) return pass1;

        // Pass 2: deduplicate by text prefix (catches different-title but same-content chunks)
        List<DocumentChunk> result = new ArrayList<>();
        Set<String> seenPrefixes  = new LinkedHashSet<>();

        for (DocumentChunk chunk : pass1) {
            String text   = chunk.getText() != null ? chunk.getText() : "";
            String prefix = text.length() > DUPLICATE_TEXT_PREFIX_LEN
                    ? text.substring(0, DUPLICATE_TEXT_PREFIX_LEN).strip()
                    : text.strip();
            if (seenPrefixes.add(prefix)) {
                result.add(chunk);
            }
        }

        return result;
    }

    // ── Query normalisation ───────────────────────────────────────────────────

    private String normalizeQuery(String questionText) {
        if (questionText == null || questionText.isBlank()) return questionText;

        String lower = questionText.toLowerCase();
        List<String> expansions = new ArrayList<>();

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
        if (matches(lower, "penetration test", "pentest", "pen test", "red team")) {
            expansions.add("penetration testing pentest annual third-party red team vulnerability assessment scope");
        }
        if (matches(lower, "vulnerability", "cve", "scan", "patch", "remediat")) {
            expansions.add("vulnerability management scanning patching CVE CVSS remediation SLA critical high");
        }
        if (matches(lower, "sast", "dast", "static analysis", "dynamic analysis", "code scan")) {
            expansions.add("SAST DAST static dynamic code analysis security scanning pipeline CI/CD");
        }
        if (matches(lower, "backup", "back up", "restore", "recovery")) {
            expansions.add("backup recovery restore encrypted offsite retention tested RTO RPO");
        }
        if (matches(lower, "rto", "recovery time objective", "rpo", "recovery point objective")) {
            expansions.add("RTO recovery time objective RPO recovery point objective SLA availability disaster");
        }
        if (matches(lower, "disaster recovery", "business continuity", "bcp", "drp")) {
            expansions.add("disaster recovery business continuity plan BCP DRP failover resilience");
        }
        if (matches(lower, "cloud provider", "cloud hosting", "aws", "azure", "gcp", "google cloud")) {
            expansions.add("cloud provider AWS Azure GCP Amazon Web Services hosting infrastructure region");
        }
        if (matches(lower, "secret", "api key", "credentials", "vault", "secret management")) {
            expansions.add("secret management API key credentials vault HashiCorp rotation environment variable");
        }
        if (matches(lower, "pull request", "code review", "peer review", "merge", "branch")) {
            expansions.add("pull request code review peer review merge branch approval SDLC");
        }
        if (matches(lower, "visitor", "physical access", "data center", "badge", "cctv")) {
            expansions.add("visitor registration physical access data center badge CCTV camera log escort");
        }
        if (matches(lower, "compliance", "framework", "iso 27001", "soc 2", "nist", "gdpr", "hipaa", "pci")) {
            expansions.add("compliance framework ISO 27001 SOC 2 NIST GDPR HIPAA PCI-DSS certification audit");
        }
        if (matches(lower, "risk assessment", "risk management", "risk register")) {
            expansions.add("risk assessment risk management risk register annual threat model impact likelihood");
        }
        if (matches(lower, "security training", "security awareness", "phishing", "annual training")) {
            expansions.add("security awareness training annual phishing simulation onboarding policy acknowledgement");
        }
        if (matches(lower, "vendor", "third party", "supplier", "sub-processor", "fourth party")) {
            expansions.add("vendor third-party supplier risk assessment annual review contract DPA sub-processor");
        }
        if (matches(lower, "annual", "annually", "yearly", "once a year", "per year")) {
            expansions.add("annual annually yearly once a year periodic frequency");
        }
        if (matches(lower, "quarter", "quarterly", "every 3 months", "periodically")) {
            expansions.add("quarterly periodically regular schedule frequency review");
        }
        if (matches(lower, "enforced", "required", "mandatory", "must", "compulsory")) {
            expansions.add("enforced required mandatory enabled configured policy required");
        }
        if (matches(lower, "enabled", "active", "in place", "implemented", "deployed")) {
            expansions.add("enabled active implemented deployed configured in place enforced");
        }

        if (expansions.isEmpty()) return questionText;
        return questionText + "\n" + String.join(" ", expansions);
    }

    private boolean matches(String lowerText, String... terms) {
        for (String term : terms) {
            if (lowerText.contains(term.toLowerCase())) return true;
        }
        return false;
    }

    // ── Keyword extraction for FTS ────────────────────────────────────────────

    private String extractKeywords(String questionText) {
        if (questionText == null || questionText.isBlank()) return "security";
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

    /**
     * Builds the LLM context string from deduplicated chunks.
     *
     * Labels each chunk with only its section title (not "[Source N | title]").
     * The "[Source N | ...]" format was leaking into the LLM's Evidence: line,
     * producing stored strings like "[Source 1 | A&A-01], [Source 4 | GRC-03]"
     * instead of clean "A&A-01, GRC-03".
     */
    private String buildContext(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return "No relevant documentation found.";

        // Step 1: filter to chunks that are genuinely relevant (similarity >= 0.45).
        // With a small corpus, many chunks score 0.15–0.44 via FTS keyword overlap
        // even when they don't actually answer the question.
        // 0.45 corresponds to a combined hybrid score where the chunk is meaningfully related.
        List<DocumentChunk> usable = chunks.stream()
                .filter(c -> c.getSimilarity() >= 0.45)
                .toList();

        // Step 2: if strict filter leaves nothing, fall back to top-1 only
        // (better to give the LLM one weak chunk than 6 unrelated ones)
        if (usable.isEmpty()) {
            usable = chunks.subList(0, 1);
        }

        // Step 3: hard cap at 3 — LLM should never see more than 3 sections.
        // With qwen2.5:7b, passing >3 sections causes it to cite all of them.
        if (usable.size() > 3) {
            usable = usable.subList(0, 3);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < usable.size(); i++) {
            DocumentChunk c = usable.get(i);
            if (i > 0) sb.append("\n\n---\n\n");
            String label = c.getSectionTitle() != null ? c.getSectionTitle() : "Document";
            sb.append(String.format("[%s]\n%s", label, trimChunk(c.getText())));
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
4. For Evidence: write ONLY the single section name from [ ] that most directly answers the question.
   Never list more than one section. Never repeat a section name.
5. Always use this exact format with no other text before or after:
   Answer: <your answer>
   Evidence: <single section name>
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

    /**
     * Extracts and cleans the evidence string from the LLM response.
     *
     * Three-step cleaning process:
     *
     * Step 1 — parse the "Evidence:" line from the LLM response.
     *
     * Step 2 — strip any "[Source N | ...]" markers the LLM may still produce
     *   (defensive; shouldn't happen with the new context format but handles
     *    edge cases where the LLM deviates from the prompt).
     *
     * Step 3 — deduplicate section names.
     *   Split the evidence string on comma/semicolon, match each token against
     *   the section titles of the chunks that were actually provided to the LLM,
     *   and deduplicate by canonical title. This catches:
     *     - LLM writing "A&A-01, A&A-01" (same name twice)
     *     - LLM writing "A&A-01, A&A-01 Authentication" (same section, different label)
     *
     * Falls back gracefully: if nothing matches the known titles, use the raw
     * LLM text (minus bracket formatting) so we never silently drop evidence.
     *
     * @param response  raw LLM completion string
     * @param chunks    deduplicated chunks that were passed to the LLM
     */
    private String extractEvidence(String response, List<DocumentChunk> chunks) {
        if (response == null || response.isBlank()) return "N/A";
        String n = response.replace("\r\n", "\n").replace("\r", "\n");

        // Step 1: parse "Evidence:" line
        int idx = n.toLowerCase().indexOf("evidence:");
        if (idx == -1) return "N/A";
        String raw = n.substring(idx + "evidence:".length()).strip();
        int nl = raw.indexOf('\n');
        if (nl > 0) raw = raw.substring(0, nl).strip();
        if (raw.isBlank() || raw.equalsIgnoreCase("N/A")) return "N/A";

        // Step 2: strip "[Source N | ...]" markers (defensive)
        raw = raw.replaceAll("\\[Source\\s+\\d+\\s*\\|\\s*", "[")
                .replaceAll("\\[([^\\]]+)\\]", "$1")
                .trim();

        // Step 3: Build ordered set of canonical section titles from chunks
        // Preserve insertion order (chunks are sorted by relevance descending)
        List<String> knownTitles = chunks.stream()
                .map(c -> c.getSectionTitle() != null ? c.getSectionTitle().trim() : "")
                .filter(t -> !t.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        // Step 4: Parse LLM evidence tokens and map each to exactly one canonical title
        String[] tokens = raw.split("[,;]");
        List<String> out = new ArrayList<>();
        // Track by lowercase to catch case variations
        Set<String> seenLower = new LinkedHashSet<>();

        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) continue;

            // Priority 1: exact case-insensitive match against known titles
            String canonical = null;
            for (String title : knownTitles) {
                if (title.equalsIgnoreCase(t)) {
                    canonical = title;
                    break;
                }
            }

            // Priority 2: token is a prefix/substring of a known title (e.g. "A&A-01" matches "A&A-01 Authentication")
            // Only if no exact match found
            if (canonical == null) {
                for (String title : knownTitles) {
                    if (title.toLowerCase().startsWith(t.toLowerCase())) {
                        canonical = title;
                        break;
                    }
                }
            }

            // Priority 3: known title is contained within the token
            if (canonical == null) {
                for (String title : knownTitles) {
                    if (t.toLowerCase().contains(title.toLowerCase())) {
                        canonical = title;
                        break;
                    }
                }
            }

            // Fallback: use the raw token as-is (don't silently drop unknown evidence)
            if (canonical == null) {
                canonical = t;
            }

            // Only add if this canonical title hasn't appeared yet
            if (seenLower.add(canonical.toLowerCase())) {
                out.add(canonical);
            }
            // If already seen: skip — this is the duplicate suppression
        }

        return out.isEmpty() ? raw : String.join(", ", out);
    }

    // ── Confidence scoring ────────────────────────────────────────────────────

    /**
     * Computes confidence from rawChunks (NOT deduplicated).
     *
     * WHY rawChunks:
     * The corroboration bonus rewards multiple independent chunks all supporting
     * the same answer. This is a genuine quality signal — if chunks 51, 52, 53
     * from section A&A-01 all match the question, the document clearly covers
     * that topic thoroughly. Deduplicating before scoring would remove this signal
     * and drop scores by 8-20% on well-covered questions.
     *
     * rawChunks are the direct output of findHybrid(), sorted by combined score.
     */
    private double computeConfidence(List<DocumentChunk> rawChunks, String answer) {
        if (rawChunks.isEmpty()) return 0.05;

        double topScore = rawChunks.get(0).getSimilarity();

        double baseConfidence;
        if      (topScore >= 0.70) baseConfidence = 0.92;
        else if (topScore >= 0.55) baseConfidence = 0.82;
        else if (topScore >= 0.38) baseConfidence = 0.70;
        else if (topScore >= 0.22) baseConfidence = 0.58;
        else if (topScore >= 0.10) baseConfidence = 0.42;
        else                       baseConfidence = 0.25;

        long corroboratingChunks = rawChunks.stream()
                .skip(1)
                .filter(c -> c.getSimilarity() >= 0.18)
                .count();
        double corroborationBonus = Math.min(corroboratingChunks * 0.05, 0.15);

        double confidence = baseConfidence + corroborationBonus;

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