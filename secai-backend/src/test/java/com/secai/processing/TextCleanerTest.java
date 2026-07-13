package com.secai.processing;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TextCleanerTest {

    private final TextCleaner cleaner = new TextCleaner();

    @Test
    void removesPageNumbers() {
        String text = "Some content\nPage 1 of 42\nMore content\nPage 2 of 42\n";
        String cleaned = cleaner.clean(text);

        assertThat(cleaned).doesNotContain("Page 1 of 42");
        assertThat(cleaned).doesNotContain("Page 2 of 42");
        assertThat(cleaned).contains("Some content");
        assertThat(cleaned).contains("More content");
    }

    @Test
    void removesConfidentialWatermarks() {
        String text = "# Security Policy\nCONFIDENTIAL\nThis document is confidential.\n";
        String cleaned = cleaner.clean(text);

        // Standalone CONFIDENTIAL line removed
        String[] lines = cleaned.split("\n");
        for (String line : lines) {
            assertThat(line.trim()).isNotEqualTo("CONFIDENTIAL");
        }
        // But inline "confidential" text in sentences is kept
        assertThat(cleaned).contains("This document is confidential");
    }

    @Test
    void collapsesExcessBlankLines() {
        String text = "Para one\n\n\n\n\n\nPara two";
        String cleaned = cleaner.clean(text);
        assertThat(cleaned).doesNotContain("\n\n\n");
    }
}