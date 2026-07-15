package com.secai.dto.library;

import com.secai.domain.library.ApprovedAnswer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Returned by GET /api/library/match?questionId={id}
 *
 * Carries everything the frontend needs to render the
 * "Found approved answer — Similarity: 96%" card.
 */
public record ApprovedAnswerMatch(
        UUID            libraryEntryId,
        String          sourceQuestionText,   // the original question that was approved
        String          answerText,
        String          evidence,
        String          approvedByEmail,
        OffsetDateTime  approvedAt,

        /** 0.0–1.0 cosine similarity, e.g. 0.96 */
        double          similarity,

        /** Similarity as a display percentage, e.g. 96 */
        int             similarityPercent
) {
    public static ApprovedAnswerMatch from(ApprovedAnswer entry) {
        double sim = entry.getSimilarity();
        return new ApprovedAnswerMatch(
                entry.getId(),
                entry.getSourceQuestionText(),
                entry.getAnswerText(),
                entry.getEvidence(),
                entry.getApprovedByEmail(),
                entry.getApprovedAt(),
                sim,
                (int) Math.round(sim * 100)
        );
    }
}