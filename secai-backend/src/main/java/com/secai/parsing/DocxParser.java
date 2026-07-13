package com.secai.parsing;

import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses DOCX questionnaires by detecting numbered question lines.
 *
 * Detection heuristics (in priority order):
 * 1. Lines starting with: "1.", "1.1", "Q1.", "Q1:", "1)" etc.
 * 2. DOCX List paragraphs (Word numbered list style)
 * 3. Lines ending with "?" (question mark heuristic — fallback)
 *
 * Section headers (Heading1/2/3 Word styles) set the current category.
 * That category is inherited by all questions until the next header.
 *
 * Quality gate: if < 50% of non-empty, non-header lines match a
 * question pattern, sets low confidence flag.
 */
@Component
public class DocxParser implements QuestionnaireParser {

    /**
     * Core question number pattern from the MVP plan — extended with more prefixes.
     * Matches: "1.", "1.1", "1.1.2", "Q1.", "Q1:", "Q1 -", "Question 1:"
     */
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "^(?:(?:Question\\s+)?Q?\\d+(?:\\.\\d+)*(?:[.):-])?\\s+)(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    /** Lines that are probably just noise / headers / page markers */
    private static final Pattern NOISE_PATTERN = Pattern.compile(
            "^(?:page\\s*\\d+|\\d+\\s*of\\s*\\d+|confidential|section|instructions?)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public ParseResult parse(MultipartFile file) throws ParseException {
        List<ParsedQuestion> questions = new ArrayList<>();
        int totalLinesAttempted = 0;
        String currentCategory = "General";
        int sortOrder = 0;

        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {

            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText().trim();
                if (text.isBlank()) continue;
                if (NOISE_PATTERN.matcher(text).matches()) continue;

                String style = para.getStyle();

                // Heading paragraphs → update category, don't treat as questions
                if (style != null && (style.startsWith("Heading") || style.equals("Title"))) {
                    currentCategory = text;
                    continue;
                }

                totalLinesAttempted++;

                // Try numbered question pattern
                Matcher m = QUESTION_PATTERN.matcher(text);
                if (m.matches()) {
                    String fullNumber  = extractNumber(text);
                    String questionText = m.group(1).trim();

                    if (questionText.length() < 5) continue;

                    questions.add(new ParsedQuestion(
                            fullNumber,
                            questionText,
                            currentCategory,
                            sortOrder++
                    ));
                    continue;
                }

                // Try Word numbered list style
                if (isWordListParagraph(para) && text.length() >= 10) {
                    questions.add(new ParsedQuestion(
                            null,
                            text,
                            currentCategory,
                            sortOrder++
                    ));
                    continue;
                }

                // Fallback: lines ending with "?" that are sufficiently long
                if (text.endsWith("?") && text.length() >= 15) {
                    questions.add(new ParsedQuestion(
                            null,
                            text,
                            currentCategory,
                            sortOrder++
                    ));
                }
            }

            // Also extract questions from tables (some questionnaires use Word tables)
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<XWPFTableCell> cells = row.getTableCells();
                    if (cells.size() < 2) continue;

                    // Heuristic: if table has 2+ cols, last non-empty col with 15+ chars is the question
                    String questionText = null;
                    String category     = currentCategory;

                    for (int c = 0; c < cells.size(); c++) {
                        String cellText = cells.get(c).getText().trim();
                        if (cellText.isBlank()) continue;

                        if (c == 0 && cellText.length() < 30) {
                            // Might be a number or category
                            continue;
                        }
                        if (cellText.length() >= 15) {
                            questionText = cellText;
                        }
                    }

                    if (questionText != null) {
                        totalLinesAttempted++;
                        questions.add(new ParsedQuestion(null, questionText, category, sortOrder++));
                    }
                }
            }

        } catch (Exception e) {
            throw new ParseException("Failed to parse DOCX file: " + e.getMessage(), e);
        }

        if (questions.isEmpty()) {
            throw new ParseException(
                    "No questions found in this DOCX. "
                            + "Please ensure questions are numbered (e.g. '1.', 'Q1.') "
                            + "or formatted as Word numbered lists."
            );
        }

        return ParseResult.of(questions, totalLinesAttempted);
    }

    /**
     * Extracts the leading number from a line like "1.2.3 Do you encrypt..."
     * Returns "1.2.3" or null if no number prefix found.
     */
    private String extractNumber(String line) {
        Matcher m = Pattern.compile("^(?:Question\\s+)?(Q?\\d+(?:\\.\\d+)*)",
                        Pattern.CASE_INSENSITIVE)
                .matcher(line.trim());

        return m.find() ? m.group(1) : null;
    }

    /** True if the paragraph is part of a Word numbered/bulleted list. */
    private boolean isWordListParagraph(XWPFParagraph para) {
        return para.getNumID() != null;
    }
}