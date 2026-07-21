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
// AFTER
public record ApprovedAnswerMatch(
        UUID            libraryEntryId,
        String          sourceQuestionText,
        String          answerText,
        String          evidence,
        String          approvedByEmail,
        OffsetDateTime  approvedAt,
        double          similarity,
        int             similarityPercent,
        UUID sourceChunkId,
        // NEW — the document that originally supplied the evidence,
        // so the frontend can render a clickable source chip
        UUID            sourceDocumentId
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
                (int) Math.round(sim * 100),
                entry.getSourceChunkId(),
                entry.getSourceDocumentId()  // NEW — must be stored on ApprovedAnswer entity
        );
    }
}