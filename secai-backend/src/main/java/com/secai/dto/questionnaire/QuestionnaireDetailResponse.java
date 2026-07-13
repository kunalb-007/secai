package com.secai.dto.questionnaire;

import com.secai.domain.questionnaire.QuestionnaireStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record QuestionnaireDetailResponse(
        UUID                id,
        String              filename,
        String              originalFormat,
        QuestionnaireStatus status,
        int                 totalQuestions,
        boolean             lowConfidenceFlag,
        String              warningMessage,
        Map<String, Long>   statusCounts,     // { "PENDING": 400, "GENERATED": 0, ... }
        OffsetDateTime      uploadedAt,
        AiJobSummary        aiJob             // null if no job created yet
) {
    public record AiJobSummary(
            UUID   jobId,
            String status,
            int    totalQuestions,
            int    completedQuestions
    ) {}
}