package com.secai.service;

import com.secai.config.TenantContext;
import com.secai.domain.coverage.CoverageReport;
import com.secai.domain.coverage.CoverageReport.CategoryCoverage;
import com.secai.domain.coverage.CoverageReportRepository;
import com.secai.domain.document.DocumentChunkRepository;
import com.secai.domain.questionnaire.Question;
import com.secai.domain.questionnaire.QuestionRepository;
import com.secai.domain.questionnaire.QuestionnaireRepository;
import com.secai.dto.coverage.CoverageReportResponse;
import com.secai.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Document Coverage Analysis.
 *
 * Computes what fraction of a questionnaire's questions are answerable
 * given the current knowledge base, broken down by category.
 *
 * Algorithm:
 *   For each category in the questionnaire:
 *     1. Sample up to MAX_SAMPLE_PER_CATEGORY questions
 *     2. Embed each sampled question
 *     3. Run vector search (threshold COVERAGE_THRESHOLD, top-3)
 *     4. If ≥ 1 chunk returned → question is answerable
 *     5. category.coverage = answerable / total_in_category
 *
 * Coverage thresholds (intentionally looser than answer generation):
 *   COVERAGE_THRESHOLD = 0.30 cosine distance ≈ similarity > 0.70
 *   We are asking "could the KB answer this?" not "is this answer good?"
 *   A chunk that is topically relevant (0.70) is enough signal.
 *
 * "Missing document" suggestions:
 *   Categories with coverage < LOW_COVERAGE_THRESHOLD (0.60) are mapped
 *   to common security document types via CATEGORY_TO_DOC_MAP.
 *   Suggestions are deduplicated and sorted by specificity.
 *
 * Estimated coverage after:
 *   Simple heuristic — assume uploading suggested docs would bring each
 *   low-coverage category up to 0.85. Recompute weighted average.
 */
@Service
public class CoverageAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CoverageAnalysisService.class);

    private static final int    MAX_SAMPLE_PER_CATEGORY = 8;
    private static final double COVERAGE_THRESHOLD      = 0.30;  // cosine distance
    private static final int    RETRIEVAL_TOP_K         = 3;
    private static final double LOW_COVERAGE_THRESHOLD  = 0.60;  // below this → suggest doc

    // ── Category → likely missing document type ───────────────────────────────
    // Keys are lowercase normalised category name fragments.
    // Matched via contains() so "incident response" matches "3.1 incident response".
    private static final List<Map.Entry<String, String>> CATEGORY_TO_DOC = List.of(
            Map.entry("incident response",      "Incident Response Plan (IRP)"),
            Map.entry("incident management",    "Incident Response Plan (IRP)"),
            Map.entry("business continuity",    "Business Continuity Plan (BCP)"),
            Map.entry("disaster recovery",      "Disaster Recovery Plan (DRP)"),
            Map.entry("vendor",                 "Vendor Risk Management Policy"),
            Map.entry("third party",            "Third-Party Risk Management Policy"),
            Map.entry("supply chain",           "Supply Chain Security Policy"),
            Map.entry("physical security",      "Physical Security Policy"),
            Map.entry("personnel",              "Human Resources Security Policy"),
            Map.entry("hr security",            "Human Resources Security Policy"),
            Map.entry("change management",      "Change Management Policy"),
            Map.entry("patch management",       "Patch Management Policy"),
            Map.entry("vulnerability",          "Vulnerability Management Policy"),
            Map.entry("asset management",       "Asset Inventory & Management Policy"),
            Map.entry("asset inventory",        "Asset Inventory & Management Policy"),
            Map.entry("cryptography",           "Cryptography & Key Management Policy"),
            Map.entry("key management",         "Cryptography & Key Management Policy"),
            Map.entry("backup",                 "Backup & Recovery Policy"),
            Map.entry("data retention",         "Data Retention & Disposal Policy"),
            Map.entry("data classification",    "Data Classification Policy"),
            Map.entry("privacy",                "Privacy Policy / Data Protection Policy"),
            Map.entry("gdpr",                   "Privacy Policy / GDPR Documentation"),
            Map.entry("network security",       "Network Security Policy"),
            Map.entry("firewall",               "Network Security Policy"),
            Map.entry("secure development",     "Secure Development Lifecycle (SDLC) Policy"),
            Map.entry("software development",   "Secure Development Lifecycle (SDLC) Policy"),
            Map.entry("training",               "Security Awareness Training Program"),
            Map.entry("awareness",              "Security Awareness Training Program"),
            Map.entry("mobile device",          "Mobile Device Management (MDM) Policy"),
            Map.entry("remote work",            "Remote Work / Telework Security Policy"),
            Map.entry("cloud",                  "Cloud Security Policy")
    );

    private final CoverageReportRepository coverageRepo;
    private final QuestionnaireRepository  questionnaireRepo;
    private final QuestionRepository       questionRepo;
    private final DocumentChunkRepository  chunkRepo;
    private final EmbeddingService         embeddingService;

    public CoverageAnalysisService(
            CoverageReportRepository coverageRepo,
            QuestionnaireRepository  questionnaireRepo,
            QuestionRepository       questionRepo,
            DocumentChunkRepository  chunkRepo,
            EmbeddingService         embeddingService
    ) {
        this.coverageRepo      = coverageRepo;
        this.questionnaireRepo = questionnaireRepo;
        this.questionRepo      = questionRepo;
        this.chunkRepo         = chunkRepo;
        this.embeddingService  = embeddingService;
    }

    // ── Trigger (called after questionnaire upload) ───────────────────────────

    /**
     * Creates a PENDING coverage report record, then dispatches async analysis.
     * Called from QuestionnaireService.uploadAndParse().
     * Returns immediately — the analysis runs in the background.
     */
    @Transactional
    public void triggerAnalysis(UUID questionnaireId, UUID orgId) {
        // Delete any prior report for this questionnaire (re-upload scenario)
        coverageRepo.findByQuestionnaireId(questionnaireId)
                .ifPresent(coverageRepo::delete);

        CoverageReport report = coverageRepo.save(
                CoverageReport.builder()
                        .questionnaireId(questionnaireId)
                        .organizationId(orgId)
                        .overallCoverage(BigDecimal.ZERO)
                        .totalQuestions(0)
                        .answerableQuestions(0)
                        .categoryBreakdown(new ArrayList<>())
                        .missingDocSuggestions(new ArrayList<>())
                        .status("PENDING")
                        .build()
        );

        // Dispatch async
        runAnalysisAsync(report.getId(), questionnaireId, orgId);
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    /**
     * GET /api/questionnaires/{id}/coverage
     * Returns the latest coverage report, or a PENDING stub if not yet complete.
     */
    @Transactional(readOnly = true)
    public CoverageReportResponse getReport(UUID questionnaireId, UUID orgId) {
        // Verify org access
        questionnaireRepo.findByIdAndOrganizationId(questionnaireId, orgId)
                .orElseThrow(() -> new NotFoundException("Questionnaire not found"));

        CoverageReport report = coverageRepo
                .findByQuestionnaireIdAndOrganizationId(questionnaireId, orgId)
                .orElseGet(() -> CoverageReport.builder()
                        .questionnaireId(questionnaireId)
                        .organizationId(orgId)
                        .status("PENDING")
                        .totalQuestions(0)
                        .answerableQuestions(0)
                        .overallCoverage(BigDecimal.ZERO)
                        .categoryBreakdown(new ArrayList<>())
                        .missingDocSuggestions(new ArrayList<>())
                        .build()
                );

        return CoverageReportResponse.from(report);
    }

    // ── Force re-analysis ─────────────────────────────────────────────────────

    /**
     * POST /api/questionnaires/{id}/coverage/refresh
     * Triggered when the user uploads additional documents and wants to
     * re-check coverage without re-uploading the questionnaire.
     */
    @Transactional
    public void refreshAnalysis(UUID questionnaireId, UUID orgId) {
        questionnaireRepo.findByIdAndOrganizationId(questionnaireId, orgId)
                .orElseThrow(() -> new NotFoundException("Questionnaire not found"));

        triggerAnalysis(questionnaireId, orgId);
    }

    // ── Async analysis pipeline ───────────────────────────────────────────────

    @Async
    public void runAnalysisAsync(UUID reportId, UUID questionnaireId, UUID orgId) {
        log.info("[coverage:{}] Starting analysis for questionnaire {}", reportId, questionnaireId);

        CoverageReport report = coverageRepo.findById(reportId).orElse(null);
        if (report == null) return;

        report.setStatus("RUNNING");
        report.setStartedAt(OffsetDateTime.now());
        coverageRepo.save(report);

        try {
            // Load all questions for this questionnaire
            List<Question> allQuestions =
                    questionRepo.findByQuestionnaireIdAndOrganizationIdOrderBySortOrder(
                            questionnaireId, orgId
                    );

            if (allQuestions.isEmpty()) {
                markComplete(report, 0, 0, new ArrayList<>(), new ArrayList<>(), null);
                return;
            }

            // Group questions by category
            Map<String, List<Question>> byCategory = allQuestions.stream()
                    .collect(Collectors.groupingBy(
                            q -> q.getCategory() != null ? q.getCategory() : "General"
                    ));

            // Analyse each category
            List<CategoryCoverage> categoryResults = new ArrayList<>();
            int totalAnswerable = 0;

            for (Map.Entry<String, List<Question>> entry : byCategory.entrySet()) {
                String         category  = entry.getKey();
                List<Question> questions = entry.getValue();

                CategoryCoverage result = analyseCategory(category, questions, orgId);
                categoryResults.add(result);
                totalAnswerable += result.getAnswerableQuestions();

                log.debug("[coverage:{}] Category '{}': {}/{} answerable ({:.0f}%)",
                        reportId, category,
                        result.getAnswerableQuestions(), result.getTotalQuestions(),
                        result.getCoverage() * 100);
            }

            // Derive missing document suggestions
            List<String> suggestions = deriveSuggestions(categoryResults);

            // Estimate coverage after uploading suggested docs
            double estimatedAfter = estimateCoverageAfter(
                    allQuestions.size(), totalAnswerable, categoryResults
            );

            double overallCoverage = allQuestions.isEmpty()
                    ? 0.0
                    : (double) totalAnswerable / allQuestions.size();

            markComplete(
                    report,
                    allQuestions.size(),
                    totalAnswerable,
                    categoryResults,
                    suggestions,
                    BigDecimal.valueOf(estimatedAfter).setScale(4, RoundingMode.HALF_UP)
            );

            log.info("[coverage:{}] Analysis complete. Overall: {:.0f}% ({}/{} answerable)",
                    reportId, overallCoverage * 100, totalAnswerable, allQuestions.size());

        } catch (Exception e) {
            log.error("[coverage:{}] Analysis failed: {}", reportId, e.getMessage(), e);
            report.setStatus("FAILED");
            report.setCompletedAt(OffsetDateTime.now());
            coverageRepo.save(report);
        }
    }

    // ── Category analysis ─────────────────────────────────────────────────────

    private CategoryCoverage analyseCategory(
            String category, List<Question> questions, UUID orgId
    ) {
        int total = questions.size();

        // Sample up to MAX_SAMPLE_PER_CATEGORY questions
        List<Question> sample = questions.size() <= MAX_SAMPLE_PER_CATEGORY
                ? questions
                : sampleQuestions(questions, MAX_SAMPLE_PER_CATEGORY);

        int answerableInSample = 0;

        for (Question q : sample) {
            try {
                boolean answerable = isAnswerable(q.getQuestionText(), orgId);
                if (answerable) answerableInSample++;
            } catch (Exception e) {
                // If embedding fails for one question, skip it
                log.warn("[coverage] Embedding failed for question {}: {}", q.getId(), e.getMessage());
            }
        }

        // Extrapolate from sample to full category
        double sampleCoverage = sample.isEmpty()
                ? 0.0
                : (double) answerableInSample / sample.size();

        int estimatedAnswerable = (int) Math.round(sampleCoverage * total);
        String tier = coverageTier(sampleCoverage);
        String suggestion = sampleCoverage < LOW_COVERAGE_THRESHOLD
                ? suggestDocument(category)
                : null;

        return CategoryCoverage.builder()
                .category(category)
                .coverage(sampleCoverage)
                .totalQuestions(total)
                .answerableQuestions(estimatedAnswerable)
                .coverageTier(tier)
                .missingDocSuggestion(suggestion)
                .build();
    }

    private boolean isAnswerable(String questionText, UUID orgId) {
        float[] embedding  = embeddingService.embed(questionText);
        String  embStr     = embeddingService.toVectorString(embedding);

        List<Object[]> rows = chunkRepo.findSimilarRaw(
                orgId, embStr, COVERAGE_THRESHOLD, RETRIEVAL_TOP_K
        );
        return !rows.isEmpty();
    }

    // ── Document suggestions ──────────────────────────────────────────────────

    private List<String> deriveSuggestions(List<CategoryCoverage> categories) {
        Set<String> suggestions = new LinkedHashSet<>(); // preserves insertion order, deduplicates

        // Sort by coverage ascending so most critical suggestions come first
        categories.stream()
                .filter(c -> c.getCoverage() < LOW_COVERAGE_THRESHOLD)
                .sorted(Comparator.comparingDouble(CategoryCoverage::getCoverage))
                .forEach(c -> {
                    if (c.getMissingDocSuggestion() != null) {
                        suggestions.add(c.getMissingDocSuggestion());
                    }
                });

        return new ArrayList<>(suggestions);
    }

    private String suggestDocument(String category) {
        String lower = category.toLowerCase();
        for (Map.Entry<String, String> entry : CATEGORY_TO_DOC) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null; // no suggestion for unknown categories
    }

    // ── Estimate coverage improvement ─────────────────────────────────────────

    /**
     * Simple heuristic: assume uploading suggested documents would bring
     * each low-coverage category up to TARGET_AFTER_UPLOAD (0.85).
     * Recompute weighted average across all categories.
     */
    private double estimateCoverageAfter(
            int total, int currentAnswerable, List<CategoryCoverage> categories
    ) {
        if (total == 0) return 0.0;
        final double TARGET_AFTER = 0.85;

        int projectedAnswerable = categories.stream()
                .mapToInt(c -> {
                    if (c.getCoverage() >= LOW_COVERAGE_THRESHOLD) {
                        return c.getAnswerableQuestions(); // already good, no change
                    }
                    // Low coverage — estimate it would reach TARGET_AFTER
                    return (int) Math.round(TARGET_AFTER * c.getTotalQuestions());
                })
                .sum();

        return Math.min(1.0, (double) projectedAnswerable / total);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Question> sampleQuestions(List<Question> questions, int n) {
        // Distribute sample evenly across the list (not just the first N)
        List<Question> sample = new ArrayList<>();
        int step = questions.size() / n;
        for (int i = 0; i < n; i++) {
            sample.add(questions.get(i * step));
        }
        return sample;
    }

    private String coverageTier(double coverage) {
        if (coverage >= 0.85) return "HIGH";
        if (coverage >= 0.60) return "MEDIUM";
        if (coverage >= 0.35) return "LOW";
        return "CRITICAL";
    }

    @Transactional
    private void markComplete(
            CoverageReport report,
            int totalQ,
            int answerable,
            List<CategoryCoverage> categories,
            List<String> suggestions,
            BigDecimal estimatedAfter
    ) {
        double overall = totalQ == 0 ? 0.0 : (double) answerable / totalQ;
        report.setStatus("COMPLETE");
        report.setTotalQuestions(totalQ);
        report.setAnswerableQuestions(answerable);
        report.setOverallCoverage(
                BigDecimal.valueOf(overall).setScale(4, RoundingMode.HALF_UP)
        );
        report.setCategoryBreakdown(categories);
        report.setMissingDocSuggestions(suggestions);
        report.setEstimatedCoverageAfter(estimatedAfter);
        report.setCompletedAt(OffsetDateTime.now());
        coverageRepo.save(report);
    }
}