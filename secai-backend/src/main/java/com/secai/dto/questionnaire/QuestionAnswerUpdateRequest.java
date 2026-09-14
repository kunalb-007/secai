package com.secai.dto.questionnaire;

public record QuestionAnswerUpdateRequest(
        String manualAnswer   // the reviewer's edited text
) {}