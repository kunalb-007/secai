package com.secai.processing;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker(300, 50);

    @Test
    void singleSection_fitsInOneChunk() {
        String text = "# Encryption Policy\n\nAll data is encrypted using AES-256.";
        List<TextChunker.Chunk> chunks = chunker.chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sectionTitle()).isEqualTo("Encryption Policy");
        assertThat(chunks.get(0).text()).contains("AES-256");
    }

    @Test
    void multipleSections_createsMultipleChunks() {
        String text = """
            # Access Control
            MFA is required for all users.
            
            ## Single Sign-On
            SSO is implemented via SAML 2.0.
            
            # Incident Response
            All incidents are logged within 1 hour.
            """;

        List<TextChunker.Chunk> chunks = chunker.chunk(text);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).sectionTitle()).isEqualTo("Access Control");
    }

    @Test
    void longSection_splitsFurtherWithOverlap() {
        // 500+ tokens worth of text
        String longParagraph = "A".repeat(2500);
        String text = "# Large Section\n\n" + longParagraph;

        List<TextChunker.Chunk> chunks = chunker.chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        // All chunks should have the same section title
        chunks.forEach(c -> assertThat(c.sectionTitle()).isEqualTo("Large Section"));
        // Chunk indices should be sequential
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).chunkIndex()).isEqualTo(i);
        }
    }

    @Test
    void emptyText_returnsEmptyList() {
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("   \n\n   ")).isEmpty();
    }

    @Test
    void noHeadings_treatsWholeDocAsOneSection() {
        String text = "This document has no headings. "
                + "It should still be chunked as a single section called 'Document'.";
        List<TextChunker.Chunk> chunks = chunker.chunk(text);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).sectionTitle()).isEqualTo("Document");
    }
}