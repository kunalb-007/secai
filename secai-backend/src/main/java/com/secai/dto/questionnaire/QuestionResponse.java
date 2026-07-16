package com.secai.dto.questionnaire;

import com.secai.domain.questionnaire.QuestionStatus;
import java.util.UUID;

public record QuestionResponse(
        UUID           id,
        String         questionNumber,
        String         questionText,
        String         category,
        String         aiAnswer,
        String         evidence,
        Double         retrievalScore,
        QuestionStatus status,
        String         manualAnswer,
        int            sortOrder,
        UUID           sourceChunkId,      // NEW — null when no evidence found
        UUID           sourceDocumentId    // NEW — null when no evidence found
) {}