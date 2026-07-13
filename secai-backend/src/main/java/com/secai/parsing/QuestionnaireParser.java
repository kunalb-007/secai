package com.secai.parsing;

import org.springframework.web.multipart.MultipartFile;

/**
 * Strategy interface for parsing a questionnaire file into a list of questions.
 * One implementation per supported format: XLSX, CSV, DOCX.
 */
public interface QuestionnaireParser {
    ParseResult parse(MultipartFile file) throws ParseException;
}