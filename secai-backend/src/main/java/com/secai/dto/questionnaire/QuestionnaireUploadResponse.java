package com.secai.dto.questionnaire;

import com.secai.domain.questionnaire.QuestionnaireStatus;
import java.util.List;
import java.util.UUID;

public record QuestionnaireUploadResponse(
        UUID                  questionnaireId,
        String                filename,
        QuestionnaireStatus   status,
        int                   totalQuestions,
        boolean               lowConfidenceFlag,
        String                warningMessage,      // null unless low confidence
        List<QuestionPreview> preview             // first 10 questions
) {
    public record QuestionPreview(
            String questionNumber,
            String questionText,
            String category
    ) {}
}