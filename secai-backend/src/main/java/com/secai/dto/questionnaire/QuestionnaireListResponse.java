package com.secai.dto.questionnaire;

import com.secai.domain.questionnaire.QuestionnaireStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record QuestionnaireListResponse(
        UUID                id,
        String              filename,
        String              originalFormat,
        QuestionnaireStatus status,
        int                 totalQuestions,
        boolean             lowConfidenceFlag,
        OffsetDateTime      uploadedAt
) {}