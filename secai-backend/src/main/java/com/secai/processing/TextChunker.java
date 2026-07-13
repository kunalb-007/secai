package com.secai.processing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Splits cleaned document text into chunks suitable for embedding.
 *
 * Strategy:
 * 1. Split at Markdown heading boundaries (# H1, ## H2, ### H3)
 *    — works for both Marker PDF output and DOCX-converted-to-Markdown
 * 2. If a section exceeds maxTokens, split further at paragraph breaks
 *    with 50-token overlap between chunks
 * 3. Each chunk carries its section_title for citation in AI answers
 *
 * Token counting: approximate (1 token ≈ 4 chars for English text).
 * Good enough for chunking; actual token count irrelevant for MVP.
 */
@Component
public class TextChunker {

    // Matches Markdown headings: "# Title", "## Section", "### Sub"
    private static final Pattern HEADING = Pattern.compile(
            "^(#{1,3})\\s+(.+)$"
    );

    private final int maxTokens;
    private final int overlapTokens;

    public TextChunker(
            @Value("${app.processing.chunk-max-tokens:300}") int maxTokens,
            @Value("${app.processing.chunk-overlap-tokens:50}") int overlapTokens
    ) {
        this.maxTokens    = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    public record Chunk(
            String sectionTitle,
            String text,
            int    chunkIndex,
            int    tokenCount
    ) {}

    /**
     * Main entry point. Takes cleaned text, returns ordered list of chunks.
     */
    public List<Chunk> chunk(String cleanedText) {
        if (cleanedText == null || cleanedText.isBlank()) return List.of();

        List<Section> sections = splitIntoSections(cleanedText);
        List<Chunk>   chunks   = new ArrayList<>();
        int chunkIndex = 0;

        for (Section section : sections) {
            List<Chunk> sectionChunks = chunkSection(section, chunkIndex);
            chunks.addAll(sectionChunks);
            chunkIndex += sectionChunks.size();
        }

        return chunks;
    }

    // ---- Internal types ----

    private record Section(String title, String body) {}

    /**
     * Step 1: Split the full document into sections at heading boundaries.
     * Each section has a title (from the heading) and a body (the text below it).
     */
    private List<Section> splitIntoSections(String text) {
        List<Section>  sections       = new ArrayList<>();
        String[]       lines          = text.split("\n");
        String currentTitle = null;
        StringBuilder currentBody = new StringBuilder();
        boolean headingFound = false;

        for (String line : lines) {
            var matcher = HEADING.matcher(line.trim());
            if (matcher.matches()) {

                headingFound = true;

                if (!currentBody.toString().isBlank()) {

                    sections.add(new Section(
                            currentTitle == null ? "Document" : currentTitle,
                            currentBody.toString().strip()
                    ));
                }

                currentTitle = matcher.group(2).trim();
                currentBody = new StringBuilder();
            } else {
                currentBody.append(line).append("\n");
            }
        }

        // Flush last section
        if (!currentBody.toString().isBlank()) {

            sections.add(new Section(
                    headingFound
                            ? currentTitle
                            : "Document",
                    currentBody.toString().strip()
            ));
        }

//        // If no sections found (no headings), treat whole document as one section
//        if (sections.isEmpty() && !text.isBlank()) {
//            sections.add(new Section("Document", text.strip()));
//        }

        return sections;
    }

    /**
     * Step 2: For each section, split body into token-sized chunks with overlap.
     * Splits at paragraph boundaries where possible to avoid mid-sentence cuts.
     */
    private List<Chunk> chunkSection(Section section, int startIndex) {
        List<Chunk> chunks = new ArrayList<>();
        String body = section.body();

        if (estimateTokens(body) <= maxTokens) {
            // Section fits in one chunk — no splitting needed
            chunks.add(new Chunk(
                    section.title(), body, startIndex, estimateTokens(body)
            ));
            return chunks;
        }

        // Split into paragraphs
        String[] paragraphs = body.split("\n\n+");
        StringBuilder current = new StringBuilder();
        int idx = startIndex;

        for (String paragraph : paragraphs) {
            int currentTokens    = estimateTokens(current.toString());
            int paragraphTokens  = estimateTokens(paragraph);

            if (paragraphTokens > maxTokens) {

                if (!current.toString().isBlank()) {

                    String chunkText = current.toString().strip();

                    chunks.add(new Chunk(
                            section.title(),
                            chunkText,
                            idx++,
                            estimateTokens(chunkText)
                    ));

                    current = new StringBuilder();
                }

                idx = splitLargeParagraph(
                        section.title(),
                        paragraph,
                        idx,
                        chunks
                );

                continue;
            }

            if (currentTokens + paragraphTokens > maxTokens && currentTokens > 0) {
                // Flush current chunk
                String chunkText = current.toString().strip();
                chunks.add(new Chunk(section.title(), chunkText, idx++, estimateTokens(chunkText)));

                // Build overlap for next chunk from tail of current chunk
                String overlapText = buildOverlap(chunkText, overlapTokens);
                current = new StringBuilder();

                if (!overlapText.isBlank()) {
                    current.append(overlapText).append("\n\n");
                }

// If overlap + current paragraph would exceed maxTokens,
// don't carry overlap into the next chunk.
                if (estimateTokens(current.toString()) + paragraphTokens > maxTokens) {
                    current = new StringBuilder();
                }
            }

            current.append(paragraph).append("\n\n");
        }

        // Flush remaining text
        String remaining = current.toString().strip();
        if (!remaining.isBlank()) {
            chunks.add(new Chunk(section.title(), remaining, idx, estimateTokens(remaining)));
        }

        return chunks;
    }

    /**
     * Extract the last N tokens worth of text for overlap.
     * Takes the last overlapTokens * 4 characters (approximate).
     */
    private String buildOverlap(String text, int overlapTokens) {
        int targetChars = overlapTokens * 4;
        if (text.length() <= targetChars) return text;

        // Try to find a sentence or paragraph boundary near the overlap point
        String tail = text.substring(text.length() - targetChars);
        int sentenceBreak = findSentenceBreak(tail);
        return sentenceBreak > 0 ? tail.substring(sentenceBreak).trim() : tail.trim();
    }

    /**
     * Find a good sentence boundary (". " or "\n") to start overlap at.
     */
    private int findSentenceBreak(String text) {
        for (int i = 0; i < Math.min(text.length(), 100); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '\n') return i + 1;
        }
        return -1;
    }

    /**
     * Approximate token count: 1 token ≈ 4 characters for English.
     * Accurate enough for chunking decisions — actual OpenAI token count
     * is not needed here.
     */
    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }

    private int splitLargeParagraph(
            String sectionTitle,
            String paragraph,
            int startIndex,
            List<Chunk> chunks
    ) {

        int maxChars = maxTokens * 4;
        int overlapChars = overlapTokens * 4;

        int start = 0;
        int index = startIndex;

        while (start < paragraph.length()) {

            int end = Math.min(start + maxChars, paragraph.length());

// Prefer ending at a word boundary instead of splitting a word
            int adjustedEnd = end;

            while (adjustedEnd > start
                    && adjustedEnd < paragraph.length()
                    && !Character.isWhitespace(paragraph.charAt(adjustedEnd - 1))) {

                adjustedEnd--;
            }

// Fallback if no whitespace was found
            if (adjustedEnd <= start) {
                adjustedEnd = end;
            }

            String text = paragraph.substring(start, adjustedEnd);

            chunks.add(new Chunk(
                    sectionTitle,
                    text,
                    index++,
                    estimateTokens(text)
            ));

            if (adjustedEnd >= paragraph.length()) {
                break;
            }

// Start next chunk with overlap
            start = Math.max(0, adjustedEnd - overlapChars);
        }

        return index;
    }
}