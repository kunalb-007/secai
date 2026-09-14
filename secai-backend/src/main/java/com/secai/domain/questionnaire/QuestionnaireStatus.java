package com.secai.domain.questionnaire;

public enum QuestionnaireStatus {
    UPLOADED,       // file stored, parsing complete or in progress
    PARSED,         // questions extracted, ready for AI generation
    GENERATING,     // AI is answering questions (Phase 5)
    COMPLETED,      // all questions answered, ready for review
    FAILED          // generation pipeline failed catastrophically
}