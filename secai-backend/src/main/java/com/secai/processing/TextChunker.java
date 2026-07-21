package com.secai.processing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Splits cleaned document text into chunks suitable for embedding.
 *
 * ROOT CAUSE FIX — "Only 6 chunks from 5,375 chars":
 *
 * The original chunker split only at Markdown headings (# ## ###).
 * Plain .txt files and many DOCX files have NO markdown headings,
 * so the entire document fell into a single "Document" section.
 * The paragraph splitter then hit the MAX_CONTEXT_CHARS guard and
 * produced only ~6 large chunks instead of 20–30 granular ones.
 *
 * Fix strategy — 3-tier splitting:
 *   Tier 1: Markdown headings (# H1, ## H2, ### H3) — as before
 *   Tier 2: ALL-CAPS section labels ("ACCESS CONTROL", "3. ENCRYPTION")
 *            common in plain-text security docs and older DOCX exports
 *   Tier 3: Numbered list entries ("1.", "1.1", "A.") at line start
 *            common in SOC2 evidence packages and security policies
 *
 * After section splitting, each section body is further chunked at
 * paragraph boundaries with 50-token overlap, same as before.
 *
 * For completely flat text (no structure at all), a sliding-window
 * sentence-aware chunker is used as final fallback.
 */
@Component
public class TextChunker {

    // ── Heading patterns ──────────────────────────────────────────────────────

    /** Markdown heading: "# Title", "## Section", "### Sub" */
    private static final Pattern MARKDOWN_HEADING = Pattern.compile(
            "^(#{1,3})\\s+(.+)$"
    );

    /**
     * ALL-CAPS section label: at least 3 words or 8 chars, no leading digits.
     * Matches: "ACCESS CONTROL", "ENCRYPTION AND KEY MANAGEMENT"
     * Rejects:  "YES", "NO", "N/A", "TLS 1.3" (short / has digits)
     */
    private static final Pattern ALLCAPS_SECTION = Pattern.compile(
            "^([A-Z][A-Z\\s/&\\-]{6,})$"
    );

    /**
     * Numbered section label: "1.", "1.1", "1.1.2", "A.", "CC6.1"
     * Must be at line start, followed by a space and a title word.
     * Min title length 4 chars to avoid matching list items like "1. Yes".
     */
//    private static final Pattern NUMBERED_SECTION = Pattern.compile(
//            "^(\\d+\\.(?:\\d+\\.?)*|[A-Z]\\.)\\s+([A-Z].{3,})$"
//    );

    private static final Pattern NUMBERED_SECTION = Pattern.compile(
            "^((?:CC\\d+(?:\\.\\d+)*)|(?:\\d+(?:\\.\\d+)*)|(?:[A-Z]\\.?))\\s+(.{3,})$"
    );

    /**
     * Completely flat fallback: treat a blank line between two non-empty
     * paragraphs as a soft split point.
     */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile(
            "\\n{2,}"
    );


    // ── Config ────────────────────────────────────────────────────────────────

    private final int maxTokens;
    private final int overlapTokens;

    /**
     * Minimum tokens a section body must have to be emitted as its own chunk.
     * Sections shorter than this are merged with the previous chunk to avoid
     * emitting tiny fragments that contain no answerable content.
     */
    private static final int MIN_SECTION_TOKENS = 20;

    public TextChunker(
            @Value("${app.processing.chunk-max-tokens:300}") int maxTokens,
            @Value("${app.processing.chunk-overlap-tokens:50}") int overlapTokens
    ) {
        this.maxTokens     = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    // ── Public record ─────────────────────────────────────────────────────────

    public record Chunk(
            String sectionTitle,
            String text,
            int    chunkIndex,
            int    tokenCount
    ) {}

    // ── Main entry point ──────────────────────────────────────────────────────

    public List<Chunk> chunk(String cleanedText) {
        if (cleanedText == null || cleanedText.isBlank()) return List.of();

        List<Section> sections = splitIntoSections(cleanedText);

        // If splitting produced only 1 section AND it's large, try paragraph fallback
        if (sections.size() == 1 && estimateTokens(sections.get(0).body()) > maxTokens * 2) {
            sections = splitByParagraphs(cleanedText);
        }

        List<Chunk>   chunks     = new ArrayList<>();
        int           chunkIndex = 0;

        for (Section section : sections) {
            List<Chunk> sectionChunks = chunkSection(section, chunkIndex);
            chunks.addAll(sectionChunks);
            chunkIndex += sectionChunks.size();
        }

        return chunks;
    }

    // ── Section detection ─────────────────────────────────────────────────────

    private record Section(String title, String body) {}

    /**
     * 3-tier heading detection — tries each pattern in priority order,
     * falls back to the next tier if no headings are found.
     */
    private List<Section> splitIntoSections(String text) {
        // Tier 1: Markdown headings
        List<Section> sections = splitByPattern(text, HeadingType.MARKDOWN);
        if (sections.size() > 1) return sections;

        // Tier 2: ALL-CAPS section labels
        sections = splitByPattern(text, HeadingType.ALLCAPS);
        if (sections.size() > 1) return sections;

        // Tier 3: Numbered section labels
        sections = splitByPattern(text, HeadingType.NUMBERED);
        if (sections.size() > 1) return sections;

        // No structure found — return as one section for paragraph fallback
        return List.of(new Section("Document", text.strip()));
    }

    private enum HeadingType { MARKDOWN, ALLCAPS, NUMBERED }

    private List<Section> splitByPattern(String text, HeadingType type) {
        List<Section>  sections     = new ArrayList<>();
        String[]       lines        = text.split("\n");
        String         currentTitle = "Document";
        StringBuilder  currentBody  = new StringBuilder();
        boolean        foundHeading = false;

        for (String line : lines) {
            String trimmed = line.trim();
            String headingTitle = detectHeading(trimmed, type);

            if (headingTitle != null) {
                foundHeading = true;
                String bodyText = currentBody.toString().strip();
                if (estimateTokens(bodyText) >= MIN_SECTION_TOKENS) {
                    sections.add(new Section(currentTitle, bodyText));
                } else if (!sections.isEmpty() && !bodyText.isEmpty()) {
                    // Merge short orphan body into previous section
                    Section prev = sections.remove(sections.size() - 1);
                    sections.add(new Section(prev.title(), prev.body() + "\n\n" + bodyText));
                }
                currentTitle = headingTitle;
                currentBody  = new StringBuilder();
            } else {
                currentBody.append(line).append("\n");
            }
        }

        // Flush last section
        String lastBody = currentBody.toString().strip();
        if (!lastBody.isBlank()) {
            sections.add(new Section(currentTitle, lastBody));
        }

        if (!foundHeading) return List.of(new Section("Document", text.strip()));
        return sections;
    }

    /** Returns the heading label if the line matches the given type, else null. */
    private String detectHeading(String line, HeadingType type) {
        return switch (type) {
            case MARKDOWN -> {
                var m = MARKDOWN_HEADING.matcher(line);
                yield m.matches() ? m.group(2).trim() : null;
            }
            case ALLCAPS -> {
                // Must be ALL_CAPS, at least 8 chars, no digits (avoids matching "AES-256 GCM")
                var m = ALLCAPS_SECTION.matcher(line);
                if (!m.matches()) yield null;
                // Reject if it contains 2+ consecutive digits (data value, not a title)
                if (line.matches(".*\\d{2,}.*")) yield null;
                yield line.trim();
            }
            case NUMBERED -> {
                var m = NUMBERED_SECTION.matcher(line);
                yield m.matches() ? m.group(1) + " " + m.group(2) : null;
            }
        };
    }

    /**
     * Paragraph-based fallback when no structural headings are found.
     * Groups consecutive paragraphs until maxTokens is reached, then flushes.
     * Each flush carries an auto-generated title from the first sentence.
     */
    private List<Section> splitByParagraphs(String text) {
        String[]      paragraphs = PARAGRAPH_BREAK.split(text);
        List<Section> sections   = new ArrayList<>();
        StringBuilder current    = new StringBuilder();
        String        title      = "Document";

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isBlank()) continue;

            if (current.length() == 0) {
                // First paragraph in this section becomes the title (first 60 chars)
                title = trimmed.length() > 60 ? trimmed.substring(0, 60) + "…" : trimmed;
            }

            if (estimateTokens(current.toString()) + estimateTokens(trimmed) > maxTokens
                    && current.length() > 0) {
                sections.add(new Section(title, current.toString().strip()));
                title   = trimmed.length() > 60 ? trimmed.substring(0, 60) + "…" : trimmed;
                current = new StringBuilder();
            }

            current.append(trimmed).append("\n\n");
        }

        if (current.length() > 0) {
            sections.add(new Section(title, current.toString().strip()));
        }

        return sections.isEmpty()
                ? List.of(new Section("Document", text.strip()))
                : sections;
    }

    // ── Section → chunks ──────────────────────────────────────────────────────

    private List<Chunk> chunkSection(Section section, int startIndex) {
        List<Chunk> chunks = new ArrayList<>();
        String body = section.body();

        if (estimateTokens(body) <= maxTokens) {
            chunks.add(new Chunk(section.title(), body, startIndex, estimateTokens(body)));
            return chunks;
        }

        String[] paragraphs = body.split("\n\n+");
        StringBuilder current = new StringBuilder();
        int idx = startIndex;

        for (String paragraph : paragraphs) {
            int currentTokens   = estimateTokens(current.toString());
            int paragraphTokens = estimateTokens(paragraph);

            // Paragraph alone exceeds limit — split it at word boundaries
            if (paragraphTokens > maxTokens) {
                if (!current.toString().isBlank()) {
                    String ct = current.toString().strip();
                    chunks.add(new Chunk(section.title(), ct, idx++, estimateTokens(ct)));
                    current = new StringBuilder();
                }
                idx = splitLargeParagraph(section.title(), paragraph, idx, chunks);
                continue;
            }

            if (currentTokens + paragraphTokens > maxTokens && currentTokens > 0) {
                String ct = current.toString().strip();
                chunks.add(new Chunk(section.title(), ct, idx++, estimateTokens(ct)));

                String overlap = buildOverlap(ct, overlapTokens);
                current = new StringBuilder();
                if (!overlap.isBlank() &&
                        estimateTokens(overlap) + paragraphTokens <= maxTokens) {
                    current.append(overlap).append("\n\n");
                }
            }

            current.append(paragraph).append("\n\n");
        }

        String remaining = current.toString().strip();
        if (!remaining.isBlank()) {
            chunks.add(new Chunk(section.title(), remaining, idx, estimateTokens(remaining)));
        }

        return chunks;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int splitLargeParagraph(String title, String paragraph, int startIdx,
                                    List<Chunk> chunks) {
        int maxChars     = maxTokens * 4;
        int overlapChars = overlapTokens * 4;
        int start        = 0;
        int idx          = startIdx;

        while (start < paragraph.length()) {
            int end = Math.min(start + maxChars, paragraph.length());

            // Retreat to a word boundary
            int adjusted = end;
            while (adjusted > start && adjusted < paragraph.length()
                    && !Character.isWhitespace(paragraph.charAt(adjusted - 1))) {
                adjusted--;
            }
            if (adjusted <= start) adjusted = end;

            String t = paragraph.substring(start, adjusted);
            chunks.add(new Chunk(title, t, idx++, estimateTokens(t)));

            if (adjusted >= paragraph.length()) break;
            start = Math.max(0, adjusted - overlapChars);
        }

        return idx;
    }

    private String buildOverlap(String text, int overlapTokens) {
        int targetChars = overlapTokens * 4;
        if (text.length() <= targetChars) return text;
        String tail = text.substring(text.length() - targetChars);
        int sb = findSentenceBreak(tail);
        return sb > 0 ? tail.substring(sb).trim() : tail.trim();
    }

    private int findSentenceBreak(String text) {
        for (int i = 0; i < Math.min(text.length(), 100); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '\n') return i + 1;
        }
        return -1;
    }

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }
}