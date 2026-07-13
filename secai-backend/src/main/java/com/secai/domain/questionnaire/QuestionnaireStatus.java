package com.secai.domain.questionnaire;

/**
 * Phase 5 REPLACEMENT for QuestionnaireStatus.
 *
 * Adds FAILED status so the questionnaire can reflect a catastrophic
 * generation failure (not just individual question failures, which are
 * handled gracefully per-question).
 *
 * REPLACE the existing QuestionnaireStatus.java with this version.
 */
public enum QuestionnaireStatus {
    UPLOADED,       // file stored, parsing complete or in progress
    PARSED,         // questions extracted, ready for AI generation
    GENERATING,     // AI is answering questions (Phase 5)
    COMPLETED,      // all questions answered, ready for review
    FAILED          // generation pipeline failed catastrophically
}