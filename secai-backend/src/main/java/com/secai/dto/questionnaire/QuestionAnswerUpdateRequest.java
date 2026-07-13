package com.secai.dto.questionnaire;

/**
 * Request body for PUT /api/questions/{id}
 * Allows the human reviewer to save an edited answer.
 */
public record QuestionAnswerUpdateRequest(
        String manualAnswer   // the reviewer's edited text
) {}