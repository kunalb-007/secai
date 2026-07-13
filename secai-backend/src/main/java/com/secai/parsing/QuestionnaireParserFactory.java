package com.secai.parsing;

import org.springframework.stereotype.Component;

/**
 * Selects the correct parser based on file extension / content type.
 * Returns the matching QuestionnaireParser implementation.
 */
@Component
public class QuestionnaireParserFactory {

    private final XlsxParser xlsxParser;
    private final CsvParser  csvParser;
    private final DocxParser docxParser;

    public QuestionnaireParserFactory(
            XlsxParser xlsxParser,
            CsvParser  csvParser,
            DocxParser docxParser
    ) {
        this.xlsxParser = xlsxParser;
        this.csvParser  = csvParser;
        this.docxParser = docxParser;
    }

    public QuestionnaireParser forFile(String filename, String contentType) {
        String lower = filename == null ? "" : filename.toLowerCase();

        if (lower.endsWith(".xlsx") || isContentType(contentType,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
            return xlsxParser;
        }
        if (lower.endsWith(".csv") || isContentType(contentType, "text/csv")
                || isContentType(contentType, "application/csv")) {
            return csvParser;
        }
        if (lower.endsWith(".docx") || isContentType(contentType,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
            return docxParser;
        }

        throw new IllegalArgumentException(
                "Unsupported questionnaire format. Supported: XLSX, CSV, DOCX. "
                        + "Got: " + filename
        );
    }

    private boolean isContentType(String actual, String expected) {
        return actual != null && actual.toLowerCase().contains(expected);
    }

    public String detectFormat(String filename) {
        if (filename == null) return "UNKNOWN";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".xlsx")) return "XLSX";
        if (lower.endsWith(".csv"))  return "CSV";
        if (lower.endsWith(".docx")) return "DOCX";
        return "UNKNOWN";
    }
}