package com.secai.processing.extractor;

import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Component
public class DocxTextExtractor implements TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocxTextExtractor.class);

    /**
     * Extracts text from DOCX preserving heading structure as Markdown.
     * Word "Heading 1" → "# Title", "Heading 2" → "## Section", etc.
     * This ensures the chunker can split on section boundaries just like
     * it does for Marker's Markdown output from PDFs.
     */
    @Override
    public String extract(String filePath) throws TextExtractionException {
        try (FileInputStream fis = new FileInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            StringBuilder sb = new StringBuilder();

            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                String style = paragraph.getStyle();
                String text   = paragraph.getText().trim();

                if (text.isEmpty()) {
                    sb.append("\n");
                    continue;
                }

                // Map Word heading styles → Markdown heading syntax
                // so our chunker can split uniformly regardless of source format
                if (style != null) {
                    String s = style.toLowerCase().replace(" ", "");
                    if (s.startsWith("heading1") || s.equals("title")) {
                        sb.append("# ").append(text).append("\n\n");
                    } else if (s.startsWith("heading2") || s.equals("subtitle")) {
                        sb.append("## ").append(text).append("\n\n");
                    } else if (s.startsWith("heading3")) {
                        sb.append("### ").append(text).append("\n\n");
                    } else {
                        sb.append(text).append("\n");
                    }
                }
            }

            // Also extract text from tables
            for (XWPFTable table : doc.getTables()) {
                sb.append("\n");
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText().trim());
                    }
                    sb.append(String.join(" | ", cells)).append("\n");
                }
                sb.append("\n");
            }

            String result = sb.toString();
            log.info("POI extracted {} chars from DOCX: {}", result.length(), filePath);

            if (result.isBlank()) {
                throw new TextExtractionException("DOCX appears to be empty: " + filePath);
            }

            return result;

        } catch (TextExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new TextExtractionException(
                    "DOCX extraction failed for " + filePath + ": " + e.getMessage(), e
            );
        }
    }
}