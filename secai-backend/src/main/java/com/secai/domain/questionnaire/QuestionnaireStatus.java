package com.secai.domain.questionnaire;

public enum QuestionnaireStatus {
    UPLOADED,       // file stored, parsing in progress or not started
    PARSED,         // questions extracted, ready for AI generation
    GENERATING,     // AI is answering questions
    COMPLETED,      // all questions answered, ready for review
    FAILED          // parsing failed
}