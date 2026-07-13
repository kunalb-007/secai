package com.secai.processing.extractor;

import org.springframework.stereotype.Component;

/**
 * Picks the right TextExtractor based on the file's content type or extension.
 */
@Component
public class TextExtractorFactory {

    private final PdfTextExtractor  pdfExtractor;
    private final DocxTextExtractor docxExtractor;
    private final TxtTextExtractor  txtExtractor;

    public TextExtractorFactory(
            PdfTextExtractor  pdfExtractor,
            DocxTextExtractor docxExtractor,
            TxtTextExtractor  txtExtractor
    ) {
        this.pdfExtractor  = pdfExtractor;
        this.docxExtractor = docxExtractor;
        this.txtExtractor  = txtExtractor;
    }

    public TextExtractor forContentType(String contentType, String filename) {
        if (contentType != null) {
            if (contentType.equals("application/pdf")) return pdfExtractor;
            if (contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                return docxExtractor;
            if (contentType.equals("text/plain")) return txtExtractor;
        }

        // Fallback to extension if content-type is missing/wrong
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".pdf"))  return pdfExtractor;
            if (lower.endsWith(".docx")) return docxExtractor;
            if (lower.endsWith(".txt"))  return txtExtractor;
        }

        throw new IllegalArgumentException(
                "No extractor available for content type: " + contentType
                        + ", filename: " + filename
        );
    }
}