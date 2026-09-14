package com.secai.parsing;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.*;

/**
 * Dedicated parser for CSA CAIQ (Consensus Assessment Initiative Questionnaire).
 *
 * CAIQ v4 workbook structure:
 *   - One or more instruction/metadata sheets (skipped)
 *   - A primary "Questionnaire" sheet containing:
 *       Col A: Control ID   (e.g. "AIS.01.1", merged across sub-questions)
 *       Col B: Control Title (merged)
 *       Col C: Question ID  (e.g. "AIS.01.1.Q01")
 *       Col D: Question     (the actual question text)
 *       Col E: Answer       (ignored at parse time)
 *       Col F: Guidance     (ignored at parse time)
 *
 * Merged-cell handling:
 *   Reads the sheet's merged regions once and resolves each cell's effective
 *   value from the top-left cell of its merge region.
 *
 * Extraction strategy:
 *   - Locate the header row (contains "Question ID" or "Control ID")
 *   - For every subsequent row where Question ID column is non-blank:
 *       emit a ParsedQuestion
 *   - Category = Control Title (merged) or Control ID domain prefix if title absent
 */
public class CaiqParser {

    private static final int MAX_HEADER_SCAN = 20;

    // Known CAIQ question sheet names (case-insensitive contains)
    private static final Set<String> QUESTION_SHEET_SIGNALS = Set.of(
            "questionnaire", "caiq", "questions"
    );

    // Known non-question sheet names to skip
    private static final Set<String> SKIP_SHEET_SIGNALS = Set.of(
            "instruction", "readme", "read me", "cover", "index", "legend",
            "glossary", "table of", "contents", "notes", "info", "about",
            "guidance", "mapping", "changelog", "change log", "introduction"
    );

    public ParseResult parse(org.springframework.web.multipart.MultipartFile file)
            throws ParseException {

        List<ParsedQuestion>    questions   = new ArrayList<>();
        ParseDiagnostics.Builder diag       = ParseDiagnostics.builder()
                .format(QuestionnaireFormat.CAIQ);

        try (Workbook workbook = new XSSFWorkbook(
                file.getInputStream())) {

            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String name = sheet.getSheetName().trim();
                String nameLower = name.toLowerCase();

                if (shouldSkipSheet(nameLower)) {
                    diag.skipped(name, "metadata/instruction sheet");
                    continue;
                }

                // Build merged-cell index for this sheet
                MergedCellIndex mergedIndex = new MergedCellIndex(sheet);

                // Find header row
                HeaderCols cols = findHeaderRow(sheet, mergedIndex);
                if (cols == null) {
                    // Try as a generic fallback sheet if it looks like it has questions
                    diag.skipped(name, "no recognisable CAIQ header found");
                    continue;
                }

                diag.processed(name);
                int extractedBefore = questions.size();

                for (int r = cols.headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    // Resolve merged cells
                    String controlId    = resolveCell(row, cols.controlIdCol,    mergedIndex);
                    String controlTitle = resolveCell(row, cols.controlTitleCol, mergedIndex);
                    String questionId   = resolveCell(row, cols.questionIdCol,   mergedIndex);
                    String questionText = resolveCell(row, cols.questionCol,      mergedIndex);

                    // Skip rows that are clearly not question rows
                    if (questionText.isBlank() && questionId.isBlank()) continue;

                    diag.candidates(1);

                    if (questionText.isBlank()) {
                        diag.skippedRow();
                        continue;
                    }

                    // Use Control Title as category; fall back to domain from Control ID
                    String category = blankToNull(controlTitle);
                    if (category == null && !controlId.isBlank()) {
                        category = domainFromControlId(controlId);
                    }

                    // Question number: prefer Question ID, fall back to Control ID
                    String number = blankToNull(questionId);
                    if (number == null) number = blankToNull(controlId);

                    questions.add(new ParsedQuestion(
                            truncate(number, 45),
                            questionText.trim(),
                            category,
                            questions.size()
                    ));
                    diag.extracted(1);
                }

                if (questions.size() == extractedBefore) {
                    diag.warn("Sheet '" + name + "' was processed but yielded no questions.");
                }
            }

        } catch (Exception e) {
            throw new ParseException("Failed to parse CAIQ file: " + e.getMessage(), e);
        }

        if (questions.isEmpty()) {
            throw new ParseException(
                    "No questions extracted from CAIQ workbook. "
                            + "Please verify this is a standard CAIQ v4 export.");
        }

        ParseDiagnostics diagnostics = diag.build();
        double confidence = diagnostics.candidateRows() == 0 ? 0.0
                : (double) diagnostics.extractedRows() / diagnostics.candidateRows();
        boolean low = confidence < 0.50;
        String warning = buildWarning(questions.size(), diagnostics, low);

        return new ParseResult(questions, confidence, low, warning, diagnostics);
    }

    // ── Header detection ─────────────────────────────────────────────────────

    private record HeaderCols(
            int headerRow,
            int controlIdCol,
            int controlTitleCol,
            int questionIdCol,
            int questionCol
    ) {}

    private HeaderCols findHeaderRow(Sheet sheet, MergedCellIndex mergedIndex) {
        int limit = Math.min(MAX_HEADER_SCAN, sheet.getLastRowNum() + 1);

        for (int r = 0; r < limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            int controlIdCol    = -1;
            int controlTitleCol = -1;
            int questionIdCol   = -1;
            int questionCol     = -1;

            for (int c = 0; c < row.getLastCellNum(); c++) {
                String h = resolveCell(row, c, mergedIndex).toLowerCase().trim();
                if (h.isBlank()) continue;

                if (h.matches("control\\s*(id|identifier|#)|id|control"))
                    controlIdCol = c;
                else if (h.matches("control\\s*(title|name|description)|title"))
                    controlTitleCol = c;
                else if (h.matches("question\\s*(id|identifier|#|no\\.?)"))
                    questionIdCol = c;
                else if (h.matches("question|question\\s*text|requirement|description"))
                    questionCol = c;
            }

            // Require at least a question column to accept this as the header
            if (questionCol >= 0 || questionIdCol >= 0) {
                // If question column not found, guess it's the column after question ID
                if (questionCol < 0 && questionIdCol >= 0) {
                    questionCol = questionIdCol + 1;
                }
                return new HeaderCols(r, controlIdCol, controlTitleCol, questionIdCol, questionCol);
            }
        }
        return null;
    }

    // ── Merged-cell resolution ───────────────────────────────────────────────

    /**
     * Thin index: maps (row, col) → effective text from merge-region top-left.
     */
    private static class MergedCellIndex {
        private final Map<Long, String> cache = new HashMap<>();
        private final Sheet sheet;
        private final List<CellRangeAddress> regions;

        MergedCellIndex(Sheet sheet) {
            this.sheet   = sheet;
            this.regions = sheet.getMergedRegions();
        }

        String resolve(int row, int col) {
            long key = ((long) row << 20) | col;
            if (cache.containsKey(key)) return cache.get(key);

            for (CellRangeAddress r : regions) {
                if (r.isInRange(row, col)) {
                    Row topRow  = sheet.getRow(r.getFirstRow());
                    String val  = topRow == null ? "" : rawCellText(topRow, r.getFirstColumn());
                    // Cache every cell in this region
                    for (int rr = r.getFirstRow(); rr <= r.getLastRow(); rr++) {
                        for (int cc = r.getFirstColumn(); cc <= r.getLastColumn(); cc++) {
                            cache.put(((long) rr << 20) | cc, val);
                        }
                    }
                    return val;
                }
            }
            cache.put(key, "");
            return "";
        }

        private String rawCellText(Row row, int col) {
            Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) return "";
            return switch (cell.getCellType()) {
                case STRING  -> cell.getStringCellValue().trim();
                case NUMERIC -> {
                    double d = cell.getNumericCellValue();
                    yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
                }
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default      -> "";
            };
        }
    }

    private String resolveCell(Row row, int col, MergedCellIndex mergedIndex) {
        if (col < 0) return "";
        // First try direct cell value
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        String direct = cell == null ? "" : rawCellText(cell);
        if (!direct.isBlank()) return direct;
        // Fall back to merged region value
        return mergedIndex.resolve(row.getRowNum(), col);
    }

    private String rawCellText(Cell cell) {
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
                catch (Exception e1) {
                    try { yield String.valueOf((long) cell.getNumericCellValue()); }
                    catch (Exception e2) { yield ""; }
                }
            }
            default -> "";
        };
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean shouldSkipSheet(String nameLower) {
        for (String signal : SKIP_SHEET_SIGNALS) {
            if (nameLower.contains(signal)) return true;
        }
        return false;
    }

    /** Extracts domain prefix like "AIS" from "AIS.01.1" */
    private String domainFromControlId(String controlId) {
        if (controlId == null) return null;
        int dot = controlId.indexOf('.');
        return dot > 0 ? controlId.substring(0, dot) : controlId;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String buildWarning(int extracted, ParseDiagnostics diag, boolean low) {
        StringBuilder sb = new StringBuilder();
        if (low) {
            sb.append(String.format(
                    "Low extraction rate: %d questions from %d candidates. ",
                    extracted, diag.candidateRows()));
        }
        if (!diag.warnings().isEmpty()) {
            diag.warnings().forEach(w -> sb.append(w).append(" "));
        }
        return sb.isEmpty() ? null : sb.toString().trim();
    }
}