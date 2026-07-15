package com.secai.parsing;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses XLSX questionnaires.
 *
 * FIX (2026-07-15): Three bugs corrected:
 *
 * BUG 1 — Header scan was limited to row 0 only.
 *   Real questionnaires often have 1–5 title/logo/date rows before the header.
 *   Fix: scan the first MAX_HEADER_SCAN_ROWS rows for a recognised header row.
 *
 * BUG 2 — Positional fallback ran on title rows.
 *   When no header was found, startRow = 0, so title rows such as
 *   "CONFIDENTIAL" and "Workday Strategic Sourcing - Simple Security Questionnaire"
 *   were emitted as questions with the full title string in questionNumber,
 *   crashing with VARCHAR(50) overflow.
 *   Fix: add a dedicated isTitleOrMetadataRow() filter that rejects
 *   short single-cell rows, all-caps watermarks, and rows whose longest
 *   cell looks like a document title rather than a question.
 *
 * BUG 3 — No guard on questionNumber length.
 *   Fix: truncate questionNumber to 45 chars as a last-resort safety net
 *   (column is VARCHAR(50); 45 leaves headroom for edge cases).
 *
 * Column detection strategy (case-insensitive header matching):
 *   - "#" / "no" / "number"  → questionNumber
 *   - "category" / "section" / "domain" / "control area" → category
 *   - "question" / "requirement" / "description" → questionText  (REQUIRED)
 *   - "answer" / "response"  → pre-filled answer (ignored; used in Phase 5)
 *
 * If no header row is found within the first MAX_HEADER_SCAN_ROWS rows,
 * falls back to column position but still skips title/metadata rows.
 *
 * Multi-sheet workbooks: processes ALL non-metadata sheets.
 * Sheet name becomes default category when no category column exists.
 */
@Component
public class XlsxParser implements QuestionnaireParser {

    // How many rows to scan looking for a header before giving up
    private static final int MAX_HEADER_SCAN_ROWS = 10;

    // Maximum length written to the question_number column (VARCHAR 50 in DB)
    private static final int MAX_QUESTION_NUMBER_LENGTH = 45;

    // A real question number looks like: 1, 1.1, 1.1.2, Q1, Q-001, A.1, etc.
    // Anything longer than 20 chars is almost certainly a title / label, not a number.
    private static final int MAX_QUESTION_NUMBER_CONTENT_LENGTH = 20;

    // Pattern that genuine question-number cells match
    private static final Pattern QUESTION_NUMBER_PATTERN = Pattern.compile(
            "^[A-Za-z]{0,3}[-.]?\\d+([.\\-]\\d+)*[a-z]?$"
    );

    // Patterns that identify standalone metadata / watermark rows
    private static final Pattern METADATA_CELL_PATTERN = Pattern.compile(
            "(?i)^(confidential|proprietary|draft|internal use only|"
                    + "do not distribute|restricted|copyright|©|version|date|"
                    + "revision|prepared by|approved by|classification).*$"
    );

    private static final Pattern FORM_LABEL_PATTERN = Pattern.compile(
            "(?i)^(" +
                    "supplier\\s*name|" +
                    "company|" +
                    "company\\s*name|" +
                    "address|" +
                    "contact|" +
                    "contact\\s*name|" +
                    "phone|" +
                    "telephone|" +
                    "mobile|" +
                    "email|" +
                    "website|" +
                    "fax|" +
                    "prepared\\s*by|" +
                    "reviewed\\s*by|" +
                    "approved\\s*by|" +
                    "questionnaire|" +
                    "questions" +
                    ")\\s*:?$"
    );

    // A row is a "title row" if its longest cell is longer than this and the
    // row has very few populated cells (≤ 2).  Title rows span merged cells
    // or sit alone in a wide column.
    private static final int TITLE_CELL_MIN_LENGTH = 30;

    @Override
    public ParseResult parse(MultipartFile file) throws ParseException {
        List<ParsedQuestion> questions = new ArrayList<>();
        int totalRowsAttempted = 0;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {

            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String sheetName = sheet.getSheetName().trim();

                // Skip obviously non-question sheets by name
                if (isMetadataSheetName(sheetName)) continue;

                // ── FIX 1: Scan up to MAX_HEADER_SCAN_ROWS for the real header ──
                HeaderDetectionResult headerResult = detectHeaderRow(sheet);

                int startRow;
                ColumnMap cols;

                if (headerResult.found()) {
                    cols     = headerResult.cols();
                    startRow = headerResult.headerRowIndex() + 1; // data starts after header
                } else {
                    // No recognisable header found — use positional fallback,
                    // but start from row 0 (positional fallback will skip title rows via
                    // the isTitleOrMetadataRow() filter below).
                    cols     = positionalFallback(sheet);
                    startRow = 0;
                }

                String currentCategory = sheetName;

                for (int r = startRow; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null || isRowEmpty(row)) continue;

                    // ── FIX 2: Skip title / watermark / metadata rows ───────────
                    if (isTitleOrMetadataRow(row)) continue;

                    totalRowsAttempted++;

                    String number = cols.numberCol() >= 0
                            ? cellText(row, cols.numberCol())
                            : null;

                    String category = cols.categoryCol() >= 0
                            ? cellText(row, cols.categoryCol())
                            : null;

                    String question = cols.questionCol() >= 0
                            ? cellText(row, cols.questionCol())
                            : null;

// fallback
                    if (question == null || question.isBlank()) {
                        question = longestCell(row);
                    }

                    if (question == null || question.isBlank()) {
                        continue;
                    }

// Skip labels like "Supplier Name:"
                    if (isFormLabel(question)) {
                        continue;
                    }

// Capture section headings
                    if (isSectionHeading(question)) {
                        currentCategory = question.trim();
                        continue;
                    }

// Reject anything that doesn't resemble a question
                    if (!looksLikeQuestion(question)) {
                        continue;
                    }

// Use explicit category column if present,
// otherwise most recent section heading.
                    if (category == null || category.isBlank()) {
                        category = currentCategory;
                    }

                    String sanitisedNumber = sanitiseQuestionNumber(number);

                    if (sanitisedNumber != null &&
                            sanitisedNumber.length() > MAX_QUESTION_NUMBER_LENGTH) {

                        sanitisedNumber =
                                sanitisedNumber.substring(0, MAX_QUESTION_NUMBER_LENGTH);
                    }

                    questions.add(new ParsedQuestion(
                            sanitisedNumber,
                            question.trim(),
                            emptyToNull(category),
                            questions.size()
                    ));
                }
            }

        } catch (Exception e) {
            throw new ParseException("Failed to parse XLSX file: " + e.getMessage(), e);
        }

        return ParseResult.of(questions, totalRowsAttempted);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Header detection
    // ─────────────────────────────────────────────────────────────────────────

    private record ColumnMap(int numberCol, int categoryCol, int questionCol) {}

    private record HeaderDetectionResult(
            boolean found,
            int headerRowIndex,
            ColumnMap cols
    ) {
        static HeaderDetectionResult notFound() {
            return new HeaderDetectionResult(false, -1, null);
        }
    }

    /**
     * Scans the first MAX_HEADER_SCAN_ROWS rows looking for a row that contains
     * at least one recognised column header keyword.  Returns the first such row
     * and its column mapping.
     */
    private HeaderDetectionResult detectHeaderRow(Sheet sheet) {
        int limit = Math.min(MAX_HEADER_SCAN_ROWS, sheet.getLastRowNum() + 1);

        for (int r = 0; r < limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowEmpty(row)) continue;

            ColumnMap cols = tryParseAsHeaderRow(row);
            if (cols != null) {
                return new HeaderDetectionResult(true, r, cols);
            }
        }
        return HeaderDetectionResult.notFound();
    }

    /**
     * Attempts to interpret the given row as a header row.
     * Returns a ColumnMap if at least one recognised header keyword is found,
     * or null if this row is not a header row.
     */
    private ColumnMap tryParseAsHeaderRow(Row row) {
        int numberCol = -1, categoryCol = -1, questionCol = -1;
        boolean foundAny = false;

        for (int c = 0; c < row.getLastCellNum(); c++) {
            String header = cellText(row, c).toLowerCase().trim();
            if (header.isEmpty()) continue;

            if (header.matches("#|no\\.?|num\\.?|number|q\\s*#|q\\.?no\\.?|sr\\.?\\s*no\\.?")) {
                numberCol = c;
                foundAny  = true;
            } else if (header.matches(
                    "category|section|domain|control\\s*area|area|group|topic|theme|"
                            + "control\\s*family|security\\s*domain|sub[- ]?category")) {
                categoryCol = c;
                foundAny    = true;
            } else if (header.matches(
                    "question|requirement|description|control|item|"
                            + "question\\s*text|security\\s*question|query|ask")) {
                questionCol = c;
                foundAny    = true;
            }
            // "answer" / "response" / "guidance" columns intentionally ignored
        }

        if (!foundAny) return null;

        // If question column still not found, use the widest non-assigned column
        if (questionCol == -1) {
            questionCol = largestUnassignedCol(row, numberCol, categoryCol);
        }

        return new ColumnMap(numberCol, categoryCol, questionCol);
    }

    /**
     * Positional fallback when no header row is detected.
     * Assumes standard layout: col 0 = number (optional), widest col = question.
     */
    private ColumnMap positionalFallback(Sheet sheet) {
        // Try to infer from first non-empty, non-title row
        int lastCol = 0;
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), MAX_HEADER_SCAN_ROWS); r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowEmpty(row) || isTitleOrMetadataRow(row)) continue;
            lastCol = row.getLastCellNum();
            break;
        }

        if (lastCol >= 3) return new ColumnMap(0, 1, 2);
        if (lastCol == 2) return new ColumnMap(-1, 0, 1);
        return new ColumnMap(-1, -1, 0);
    }

    private int largestUnassignedCol(Row headerRow, int... assigned) {
        int best = -1, bestLen = 0;
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            boolean skip = false;
            for (int a : assigned) {
                if (c == a) { skip = true; break; }
            }
            if (skip) continue;
            String v = cellText(headerRow, c);
            if (v.length() > bestLen) { bestLen = v.length(); best = c; }
        }
        return best == -1 ? 0 : best;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Row classification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the row looks like a title, watermark, or metadata row
     * rather than a data row.
     *
     * Criteria (any one is sufficient):
     *   a) Exactly one non-empty cell and its value matches METADATA_CELL_PATTERN
     *   b) Row has ≤ 2 non-empty cells AND the longest cell is > TITLE_CELL_MIN_LENGTH
     *      characters AND does not end with '?' (questions often end with ?)
     *   c) The first non-empty cell is longer than MAX_QUESTION_NUMBER_CONTENT_LENGTH
     *      and has no content resembling a number (catches document-title-in-col-0)
     */
    private boolean isTitleOrMetadataRow(Row row) {
        List<String> cells = new ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            String v = cellText(row, c);
            if (!v.isBlank()) cells.add(v.trim());
        }

        if (cells.isEmpty()) return true;

        if (cells.size() == 1) {

            String value = cells.get(0).trim();

            if (isFormLabel(value))
                return true;

            if (isSectionHeading(value))
                return false;
        }

        // (a) Single-cell metadata keyword
        if (cells.size() == 1 && METADATA_CELL_PATTERN.matcher(cells.get(0)).matches()) {
            return true;
        }

        // (b) Very few cells, long content, not a question
        if (cells.size() <= 2) {
            String longest = cells.stream()
                    .max(java.util.Comparator.comparingInt(String::length))
                    .orElse("");
            if (longest.length() >= TITLE_CELL_MIN_LENGTH && !longest.endsWith("?")) {
                // Additional check: does it look like a question at all?
                // Real questions usually contain a verb or end with a question word.
                boolean looksLikeQuestion =
                        longest.toLowerCase().matches(
                                ".*(do you|does your|have you|is there|are there|"
                                        + "please describe|provide|explain|"
                                        + "how do|what is|what are|who is|when is|"
                                        + "can you|will you|\\?).*"
                        );
                if (!looksLikeQuestion) return true;
            }
        }

        // (c) First cell is too long to be a question number and contains no digits
        String firstCell = cells.get(0);
        if (firstCell.length() > MAX_QUESTION_NUMBER_CONTENT_LENGTH
                && !firstCell.matches(".*\\d.*")) {
            return true;
        }

        return false;
    }

    private boolean isFormLabel(String text) {
        if (text == null) return false;
        return FORM_LABEL_PATTERN.matcher(text.trim()).matches();
    }

    // Replace isSectionHeading() entirely:
    private boolean isSectionHeading(String text) {
        if (text == null) return false;
        text = text.trim();

        // Too long to be a heading
        if (text.length() > 80) return false;

        // Ends like a sentence or question — not a heading
        if (text.endsWith("?") || text.endsWith(".")) return false;

        long words = java.util.Arrays.stream(text.split("\\s+"))
                .filter(w -> !w.isBlank())
                .count();

        // Too many words for a section title
        if (words > 8) return false;

        // All-caps (with optional numbers, spaces, colons, hyphens) is a heading
        // e.g. "ACCESS CONTROL", "3.1 ENCRYPTION", "SECTION 2: NETWORK"
        return text.matches("[A-Z0-9\\s.:\\-]+") && text.matches(".*[A-Z].*");
    }

    private boolean looksLikeQuestion(String text) {

        if (text == null)
            return false;

        text = text.trim();

        if (text.length() < 15)
            return false;

        String lower = text.toLowerCase();

        if (lower.endsWith("?"))
            return true;

        return lower.matches(
                ".*\\b(do you|does your|have you|has your|is there|are there|" +
                        "please describe|provide|explain|identify|list|describe|" +
                        "what|how|who|when|which|can you|will you|confirm)\\b.*"
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Question number sanitisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the value if it looks like a genuine question number,
     * or null if it looks like a title / label / question text that was
     * accidentally placed in the number column.
     *
     * A genuine question number:
     *   - Is short (≤ MAX_QUESTION_NUMBER_CONTENT_LENGTH chars)
     *   - Matches QUESTION_NUMBER_PATTERN OR is a plain integer
     */
    private String sanitiseQuestionNumber(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();

        // Reject anything too long to be a question number
        if (v.length() > MAX_QUESTION_NUMBER_CONTENT_LENGTH) return null;

        // Accept if it matches the standard number pattern
        if (QUESTION_NUMBER_PATTERN.matcher(v).matches()) return v;

        // Accept plain integers
        try {
            Integer.parseInt(v);
            return v;
        } catch (NumberFormatException ignored) { /* fall through */ }

        // Accept short alphanumeric codes (e.g. "CC6.1", "AC-1", "SA.L2-3.13.1")
        if (v.matches("[A-Za-z0-9][A-Za-z0-9.\\-_]{0,18}")) return v;

        // Everything else (e.g. "CONFIDENTIAL", long titles) → null
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sheet-name metadata check
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isMetadataSheetName(String name) {
        String lower = name.toLowerCase();
        return lower.contains("instruction") || lower.contains("readme")
                || lower.contains("cover")       || lower.contains("index")
                || lower.contains("legend")      || lower.contains("glossary")
                || lower.contains("table of")    || lower.contains("contents")
                || lower.equals("notes")         || lower.equals("info")
                || lower.equals("about");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cell helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String cellText(Row row, int col) {
        if (col < 0) return "";
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) yield "";
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue().trim(); }
                catch (Exception e) {
                    try { yield String.valueOf((long) cell.getNumericCellValue()); }
                    catch (Exception e2) { yield ""; }
                }
            }
            default -> "";
        };
    }

    private String longestCell(Row row) {
        String longest = "";
        for (int c = 0; c < row.getLastCellNum(); c++) {
            String v = cellText(row, c);
            if (v.length() > longest.length()) longest = v;
        }
        return longest;
    }

    private boolean isRowEmpty(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            if (!cellText(row, c).isBlank()) return false;
        }
        return true;
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}