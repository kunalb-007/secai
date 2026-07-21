package com.secai.dto.coverage;

import java.util.List;
import java.util.UUID;

/**
 * Returned by GET /api/questionnaires/{id}/coverage
 *
 * Designed to drive the entire Coverage Analysis page without
 * any additional API calls.
 */
public record CoverageReportResponse(

        UUID   questionnaireId,
        String status,           // PENDING | RUNNING | COMPLETE | FAILED

        // ── Overall numbers ────────────────────────────────────────────────
        double overallCoverage,          // 0.0–1.0
        int    overallPercent,           // 0–100  (rounded)
        int    totalQuestions,
        int    answerableQuestions,
        int    unanswerable,             // totalQuestions - answerableQuestions

        /** Human-readable tier: EXCELLENT | GOOD | FAIR | POOR | CRITICAL */
        String coverageTier,

        /** One-line summary, e.g. "82% of questions answerable with current docs" */
        String summary,

        // ── Per-category breakdown ─────────────────────────────────────────
        List<CategoryRow> categories,

        // ── Recommendations ────────────────────────────────────────────────
        List<String> missingDocSuggestions,

        /**
         * Estimated overall coverage if suggested docs were uploaded.
         * Shown as: "Uploading missing docs could improve coverage to ~94%"
         */
        double estimatedCoverageAfter,
        int    estimatedPercentAfter

) {
    public record CategoryRow(
            String       category,
            double       coverage,
            int          coveragePercent,
            int          totalQuestions,
            int          answerableQuestions,
            String       tier,
            String       missingDocSuggestion,
            // NEW — up to 5 unanswered question texts for per-question gap display
            List<String> unansweredQuestions
    ) {}

    /** Build from the domain entity */
    public static CoverageReportResponse from(
            com.secai.domain.coverage.CoverageReport r
    ) {
        double overall = r.getOverallCoverage() != null
                ? r.getOverallCoverage().doubleValue() : 0.0;
        int pct = (int) Math.round(overall * 100);

        String tier = coverageTier(overall);
        String summary = buildSummary(pct, r.getAnswerableQuestions(), r.getTotalQuestions());

        List<CategoryRow> cats = r.getCategoryBreakdown() == null
                ? List.of()
                : r.getCategoryBreakdown().stream()
                .sorted((a, b) -> Double.compare(a.getCoverage(), b.getCoverage())) // worst first
                .map(c -> new CategoryRow(
                        c.getCategory(),
                        c.getCoverage(),
                        (int) Math.round(c.getCoverage() * 100),
                        c.getTotalQuestions(),
                        c.getAnswerableQuestions(),
                        c.getCoverageTier(),
                        c.getMissingDocSuggestion(),
                        c.getUnansweredQuestions() != null
                        ? c.getUnansweredQuestions()
                        : List.of()
                ))
                .toList();

        double estAfter = r.getEstimatedCoverageAfter() != null
                ? r.getEstimatedCoverageAfter().doubleValue() : overall;

        return new CoverageReportResponse(
                r.getQuestionnaireId(),
                r.getStatus(),
                overall,
                pct,
                r.getTotalQuestions(),
                r.getAnswerableQuestions(),
                r.getTotalQuestions() - r.getAnswerableQuestions(),
                tier,
                summary,
                cats,
                r.getMissingDocSuggestions() != null ? r.getMissingDocSuggestions() : List.of(),
                estAfter,
                (int) Math.round(estAfter * 100)
        );
    }

    private static String coverageTier(double c) {
        if (c >= 0.90) return "EXCELLENT";
        if (c >= 0.75) return "GOOD";
        if (c >= 0.55) return "FAIR";
        if (c >= 0.35) return "POOR";
        return "CRITICAL";
    }

    private static String buildSummary(int pct, int answerable, int total) {
        if (pct >= 90) return pct + "% of questions answerable — ready to generate";
        if (pct >= 75) return pct + "% of questions answerable — good coverage, minor gaps";
        if (pct >= 55) return pct + "% of questions answerable — missing key policy documents";
        return pct + "% of questions answerable — significant documents missing";
    }
}