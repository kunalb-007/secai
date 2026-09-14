package com.secai.parsing;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * XLSX parser — now format-aware.
 *
 * On every parse call:
 *   1. Open the workbook.
 *   2. Run FormatDetector to identify the questionnaire type.
 *   3. Delegate to CaiqParser (CAIQ) or the embedded generic parser (GENERIC).
 *
 * The generic parser is unchanged from the previous version (bugs already fixed).
 * Adding support for a new format means adding a branch in parse() and a new
 * dedicated parser class — no changes to existing code required.
 */
@Component
public class XlsxParser implements QuestionnaireParser {

    private static final int MAX_HEADER_SCAN_ROWS = 10;
    private static final int MAX_QUESTION_NUMBER_LENGTH = 45;
    private static final int MAX_QUESTION_NUMBER_CONTENT_LENGTH = 20;

    private static final Pattern QUESTION_NUMBER_PATTERN = Pattern.compile(
            "^[A-Za-z]{0,3}[-.]?\\d+([.\\-]\\d+)*[a-z]?$"
    );
    private static final Pattern METADATA_CELL_PATTERN = Pattern.compile(
            "(?i)^(confidential|proprietary|draft|internal use only|"
                    + "do not distribute|restricted|copyright|©|version|date|"
                    + "revision|prepared by|approved by|classification).*$"
    );
    private static final Pattern FORM_LABEL_PATTERN = Pattern.compile(
            "(?i)^(" +
                    "supplier\\s*name|company|company\\s*name|address|contact|"
                    + "contact\\s*name|phone|telephone|mobile|email|website|fax|"
                    + "prepared\\s*by|reviewed\\s*by|approved\\s*by|questionnaire|questions"
                    + ")\\s*:?$"
    );

    private static final int TITLE_CELL_MIN_LENGTH = 30;

    private final FormatDetector formatDetector = new FormatDetector();
    private final CaiqParser     caiqParser     = new CaiqParser();

    // ── Entry point ──────────────────────────────────────────────────────────

    @Override
    public ParseResult parse(MultipartFile file) throws ParseException {
        // Detect format without holding the stream open
        QuestionnaireFormat format;
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            format = formatDetector.detect(wb);
        } catch (Exception e) {
            throw new ParseException("Could not open XLSX for format detection: " + e.getMessage(), e);
        }

        return switch (format) {
            case CAIQ    -> caiqParser.parse(file);
            case GENERIC -> parseGeneric(file);
        };
    }

    // ── Generic parser (unchanged logic from previous version) ────────────────

    private ParseResult parseGeneric(MultipartFile file) throws ParseException {
        List<ParsedQuestion> questions = new ArrayList<>();
        int totalRowsAttempted = 0;

        ParseDiagnostics.Builder diag = ParseDiagnostics.builder()
                .format(QuestionnaireFormat.GENERIC);

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {

            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String sheetName = sheet.getSheetName().trim();

                if (isMetadataSheetName(sheetName)) {
                    diag.skipped(sheetName, "metadata sheet name");
                    continue;
                }

                diag.processed(sheetName);
                HeaderDetectionResult headerResult = detectHeaderRow(sheet);

                int startRow;
                ColumnMap cols;

                if (headerResult.found()) {
                    cols     = headerResult.cols();
                    startRow = headerResult.headerRowIndex() + 1;
                } else {
                    cols     = positionalFallback(sheet);
                    startRow = 0;
                }

                String currentCategory = sheetName;

                for (int r = startRow; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null || isRowEmpty(row)) continue;
                    if (isTitleOrMetadataRow(row)) continue;

                    totalRowsAttempted++;
                    diag.candidates(1);

                    String number   = cols.numberCol()   >= 0 ? cellText(row, cols.numberCol())   : null;
                    String category = cols.categoryCol() >= 0 ? cellText(row, cols.categoryCol()) : null;
                    String question = cols.questionCol() >= 0 ? cellText(row, cols.questionCol()) : null;

                    if (question == null || question.isBlank()) question = longestCell(row);
                    if (question == null || question.isBlank()) { diag.skippedRow(); continue; }
                    if (isFormLabel(question))                  { diag.skippedRow(); continue; }

                    if (isSectionHeading(question)) {
                        currentCategory = question.trim();
                        continue;
                    }

                    if (!looksLikeQuestion(question)) { diag.skippedRow(); continue; }

                    if (category == null || category.isBlank()) category = currentCategory;

                    String sanitisedNumber = sanitiseQuestionNumber(number);
                    if (sanitisedNumber != null && sanitisedNumber.length() > MAX_QUESTION_NUMBER_LENGTH) {
                        sanitisedNumber = sanitisedNumber.substring(0, MAX_QUESTION_NUMBER_LENGTH);
                    }

                    questions.add(new ParsedQuestion(
                            sanitisedNumber,
                            question.trim(),
                            emptyToNull(category),
                            questions.size()
                    ));
                    diag.extracted(1);
                }
            }

        } catch (Exception e) {
            throw new ParseException("Failed to parse XLSX file: " + e.getMessage(), e);
        }

        ParseDiagnostics diagnostics = diag.build();
        double confidence = totalRowsAttempted == 0
                ? 0.0 : (double) questions.size() / totalRowsAttempted;
        boolean low = confidence < 0.50;
        String warning = low
                ? String.format(
                "We found %d questions from %d rows (%.0f%% match rate). "
                + "Please review and add any missing questions.",
                questions.size(), totalRowsAttempted, confidence * 100)
                : null;

        return new ParseResult(questions, confidence, low, warning, diagnostics);
    }

    // ── Header detection (generic) ────────────────────────────────────────────

    private record ColumnMap(int numberCol, int categoryCol, int questionCol) {}

    private record HeaderDetectionResult(boolean found, int headerRowIndex, ColumnMap cols) {
        static HeaderDetectionResult notFound() { return new HeaderDetectionResult(false, -1, null); }
    }

    private HeaderDetectionResult detectHeaderRow(Sheet sheet) {
        int limit = Math.min(MAX_HEADER_SCAN_ROWS, sheet.getLastRowNum() + 1);
        for (int r = 0; r < limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowEmpty(row)) continue;
            ColumnMap cols = tryParseAsHeaderRow(row);
            if (cols != null) return new HeaderDetectionResult(true, r, cols);
        }
        return HeaderDetectionResult.notFound();
    }

    private ColumnMap tryParseAsHeaderRow(Row row) {
        int numberCol = -1, categoryCol = -1, questionCol = -1;
        boolean foundAny = false;

        for (int c = 0; c < row.getLastCellNum(); c++) {
            String header = cellText(row, c).toLowerCase().trim();
            if (header.isEmpty()) continue;

            if (header.matches("#|no\\.?|num\\.?|number|q\\s*#|q\\.?no\\.?|sr\\.?\\s*no\\.?")) {
                numberCol = c; foundAny = true;
            } else if (header.matches(
                    "category|section|domain|control\\s*area|area|group|topic|theme|"
                            + "control\\s*family|security\\s*domain|sub[- ]?category")) {
                categoryCol = c; foundAny = true;
            } else if (header.matches(
                    "question|requirement|description|control|item|"
                            + "question\\s*text|security\\s*question|query|ask")) {
                questionCol = c; foundAny = true;
            }
        }

        if (!foundAny) return null;
        if (questionCol == -1) questionCol = largestUnassignedCol(row, numberCol, categoryCol);
        return new ColumnMap(numberCol, categoryCol, questionCol);
    }

    private ColumnMap positionalFallback(Sheet sheet) {
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
            for (int a : assigned) { if (c == a) { skip = true; break; } }
            if (skip) continue;
            String v = cellText(headerRow, c);
            if (v.length() > bestLen) { bestLen = v.length(); best = c; }
        }
        return best == -1 ? 0 : best;
    }

    // ── Row classification ────────────────────────────────────────────────────

    private boolean isTitleOrMetadataRow(Row row) {
        List<String> cells = new ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            String v = cellText(row, c);
            if (!v.isBlank()) cells.add(v.trim());
        }
        if (cells.isEmpty()) return true;
        if (cells.size() == 1) {
            if (isFormLabel(cells.get(0))) return true;
            if (isSectionHeading(cells.get(0))) return false;
        }
        if (cells.size() == 1 && METADATA_CELL_PATTERN.matcher(cells.get(0)).matches()) return true;
        if (cells.size() <= 2) {
            String longest = cells.stream().max(java.util.Comparator.comparingInt(String::length)).orElse("");
            if (longest.length() >= TITLE_CELL_MIN_LENGTH && !longest.endsWith("?")) {
                boolean looksLikeQuestion = longest.toLowerCase().matches(
                        ".*(do you|does your|have you|is there|are there|"
                                + "please describe|provide|explain|how do|what is|what are|"
                                + "who is|when is|can you|will you|\\?).*");
                if (!looksLikeQuestion) return true;
            }
        }
        String firstCell = cells.get(0);
        if (firstCell.length() > MAX_QUESTION_NUMBER_CONTENT_LENGTH && !firstCell.matches(".*\\d.*")) return true;
        return false;
    }

    private boolean isFormLabel(String text) {
        if (text == null) return false;
        return FORM_LABEL_PATTERN.matcher(text.trim()).matches();
    }

    private boolean isSectionHeading(String text) {
        if (text == null) return false;
        text = text.trim();
        if (text.length() > 80) return false;
        if (text.endsWith("?") || text.endsWith(".")) return false;
        long words = java.util.Arrays.stream(text.split("\\s+"))
                .filter(w -> !w.isBlank()).count();
        if (words > 8) return false;
        return text.matches("[A-Z0-9\\s.:\\-]+") && text.matches(".*[A-Z].*");
    }

    private boolean looksLikeQuestion(String text) {
        if (text == null) return false;
        text = text.trim();
        if (text.length() < 15) return false;
        String lower = text.toLowerCase();
        if (lower.endsWith("?")) return true;
        return lower.matches(
                ".*\\b(do you|does your|have you|has your|is there|are there|"
                        + "please describe|provide|explain|identify|list|describe|"
                        + "what|how|who|when|which|can you|will you|confirm)\\b.*");
    }

    // ── Question number sanitisation ──────────────────────────────────────────

    private String sanitiseQuestionNumber(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.length() > MAX_QUESTION_NUMBER_CONTENT_LENGTH) return null;
        if (QUESTION_NUMBER_PATTERN.matcher(v).matches()) return v;
        try { Integer.parseInt(v); return v; } catch (NumberFormatException ignored) {}
        if (v.matches("[A-Za-z0-9][A-Za-z0-9.\\-_]{0,18}")) return v;
        return null;
    }

    // ── Sheet name check ──────────────────────────────────────────────────────

    private boolean isMetadataSheetName(String name) {
        String lower = name.toLowerCase();
        return lower.contains("instruction") || lower.contains("readme")
                || lower.contains("cover")       || lower.contains("index")
                || lower.contains("legend")      || lower.contains("glossary")
                || lower.contains("table of")    || lower.contains("contents")
                || lower.equals("notes")         || lower.equals("info")
                || lower.equals("about");
    }

    // ── Cell helpers ──────────────────────────────────────────────────────────

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