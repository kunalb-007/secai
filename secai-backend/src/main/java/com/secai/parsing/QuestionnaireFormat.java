package com.secai.parsing;

/**
 * Supported questionnaire formats for format-aware parsing.
 */
public enum QuestionnaireFormat {
    CAIQ,    // Cloud Security Alliance - Consensus Assessment Initiative Questionnaire
    GENERIC  // Standard row-based Excel questionnaire (existing behaviour)
}