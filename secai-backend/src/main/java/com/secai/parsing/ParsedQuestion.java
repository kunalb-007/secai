package com.secai.parsing;

/**
 * Raw question extracted from a file by a parser.
 * Internal use only — converted to Question entity by QuestionnaireService.
 */
public record ParsedQuestion(
        String questionNumber,   // nullable — not all formats have numbers
        String questionText,     // required
        String category,         // sheet name or section header; nullable
        int    sortOrder
) {}