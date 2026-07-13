package com.secai.processing;

import org.springframework.stereotype.Component;
import java.util.regex.*;

/**
 * Cleans extracted text before chunking.
 *
 * Removes noise that degrades embedding quality:
 * - Page numbers ("Page 1 of 42", "- 5 -", standalone numbers on their own line)
 * - Repeated watermarks ("CONFIDENTIAL", "DRAFT")
 * - Running headers/footers (short lines repeated every N lines)
 * - Excessive blank lines
 * - Control characters
 */
@Component
public class TextCleaner {

    // "Page 1 of 42", "Page 12", "1 of 42"
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "(?im)^\\s*(?:page\\s+)?\\d+\\s*(?:of\\s+\\d+)?\\s*$"
    );

    // "- 5 -", "— 12 —"
    private static final Pattern PAGE_NUMBER_DASHES = Pattern.compile(
            "(?im)^\\s*[-—]+\\s*\\d+\\s*[-—]+\\s*$"
    );

    // Standalone confidentiality markers repeated as watermarks/headers
    private static final Pattern CONFIDENTIAL_WATERMARK = Pattern.compile(
            "(?im)^\\s*(CONFIDENTIAL|PROPRIETARY|DRAFT|INTERNAL USE ONLY|DO NOT DISTRIBUTE)\\s*$"
    );

    // Three or more consecutive blank lines → collapse to one
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile(
            "\\n{3,}"
    );

    // Control characters (except \n, \r, \t)
    private static final Pattern CONTROL_CHARS = Pattern.compile(
            "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"
    );

    // Copyright / footer lines: short lines starting with © or (c)
    private static final Pattern COPYRIGHT_FOOTER = Pattern.compile(
            "(?im)^\\s*[©(c)]\\s*\\d{4}.*$"
    );

    public String clean(String rawText) {
        if (rawText == null || rawText.isBlank()) return "";

        String text = rawText;

        // Remove control characters first
        text = CONTROL_CHARS.matcher(text).replaceAll("");

        // Remove page numbers
        text = PAGE_NUMBER.matcher(text).replaceAll("");
        text = PAGE_NUMBER_DASHES.matcher(text).replaceAll("");

        // Remove watermarks
        text = CONFIDENTIAL_WATERMARK.matcher(text).replaceAll("");

        // Remove copyright footers
        text = COPYRIGHT_FOOTER.matcher(text).replaceAll("");

        // Remove repeated running headers/footers
        text = removeRepeatedLines(text);

        // Collapse excess blank lines
        text = EXCESS_BLANK_LINES.matcher(text).replaceAll("\n\n");

        return text.strip();
    }

    /**
     * Detects and removes lines that appear 3+ times in the document
     * with very short content — these are usually running headers or footers.
     */
    private String removeRepeatedLines(String text) {
        String[] lines = text.split("\n");
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();

        for (String line : lines) {
            String trimmed = line.trim();
            // Only check short lines (headers/footers are typically < 60 chars)
            if (trimmed.length() > 0 && trimmed.length() < 60) {
                counts.merge(trimmed, 1, Integer::sum);
            }
        }

        // Collect lines that appear 4+ times (repeated running header/footer)
        java.util.Set<String> repeatedLines = new java.util.HashSet<>();
        counts.forEach((line, count) -> {
            if (count >= 4) repeatedLines.add(line);
        });

        if (repeatedLines.isEmpty()) return text;

        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (!repeatedLines.contains(line.trim())) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}