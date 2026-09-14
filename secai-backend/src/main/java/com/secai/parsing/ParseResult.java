package com.secai.parsing;

import java.util.List;

/**
 * Result returned by any QuestionnaireParser implementation.
 */
public record ParseResult(
        List<ParsedQuestion> questions,
        double               confidence,
        boolean              lowConfidence,
        String               warningMessage,
        ParseDiagnostics     diagnostics        // nullable — CSV/DOCX parsers pass null
) {
    /**
     * Convenience factory for parsers without full diagnostics (CSV, DOCX).
     * Passes null for diagnostics so those parsers need zero changes.
     */
    public static ParseResult of(List<ParsedQuestion> questions, int totalRowsAttempted) {
        double confidence = totalRowsAttempted == 0
                ? 0.0
                : (double) questions.size() / totalRowsAttempted;
        boolean low = confidence < 0.50;
        String warning = low
                ? String.format(
                "We found %d questions from %d rows (%.0f%% match rate). "
                + "Please review and add any missing questions.",
                questions.size(), totalRowsAttempted, confidence * 100)
                : null;
        return new ParseResult(questions, confidence, low, warning, null);
    }
}