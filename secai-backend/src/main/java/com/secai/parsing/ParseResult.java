package com.secai.parsing;

import java.util.List;

/**
 * Result returned by any QuestionnaireParser implementation.
 */
public record ParseResult(
        List<ParsedQuestion> questions,
        double               confidence,     // 0.0–1.0: fraction of rows matched as questions
        boolean              lowConfidence,  // true when confidence < 0.5
        String               warningMessage  // nullable; shown to user when lowConfidence
) {
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
        return new ParseResult(questions, confidence, low, warning);
    }
}