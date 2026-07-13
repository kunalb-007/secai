package com.secai.parsing;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses XLSX questionnaires.
 *
 * Column detection strategy (case-insensitive header matching):
 *   - "#" / "no" / "number"  → questionNumber
 *   - "category" / "section" / "domain" / "control area" → category
 *   - "question" / "requirement" / "description" → questionText   (REQUIRED)
 *   - "answer" / "response"  → pre-filled answer (ignored in Phase 4, used in Phase 5)
 *
 * If no header row is detected, falls back to column position:
 *   Col 0 = number, Col 1 = category, Col 2 = question
 *
 * Multi-sheet workbooks: processes ALL sheets. Sheet name becomes default category.
 */
@Component
public class XlsxParser implements QuestionnaireParser {

    @Override
    public ParseResult parse(MultipartFile file) throws ParseException {
        List<ParsedQuestion> questions = new ArrayList<>();
        int totalRowsAttempted = 0;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {

            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                // Skip obviously non-question sheets
                if (isMetadataSheet(sheetName)) continue;

                // Detect column positions from header row
                ColumnMap cols = detectColumns(sheet);

                // Process data rows (skip header row 0)
                int startRow = cols.hasHeader ? 1 : 0;

                for (int r = startRow; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null || isRowEmpty(row)) continue;

                    totalRowsAttempted++;

                    String number   = cols.numberCol   >= 0 ? cellText(row, cols.numberCol)   : null;
                    String category = cols.categoryCol >= 0 ? cellText(row, cols.categoryCol)  : sheetName;
                    String question = cols.questionCol >= 0 ? cellText(row, cols.questionCol)  : null;

                    // If no question column detected, use the longest cell in the row
                    if (question == null || question.isBlank()) {
                        question = longestCell(row);
                    }

                    if (question == null || question.isBlank() || question.trim().length() < 5) {
                        continue; // skip empty/noise rows
                    }

                    questions.add(new ParsedQuestion(
                            emptyToNull(number),
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

    // ── Column detection ──────────────────────────────────────────────

    private record ColumnMap(int numberCol, int categoryCol, int questionCol, boolean hasHeader) {}

    private ColumnMap detectColumns(Sheet sheet) {
        Row firstRow = sheet.getRow(0);
        if (firstRow == null) return new ColumnMap(-1, -1, 0, false);

        int numberCol = -1, categoryCol = -1, questionCol = -1;
        boolean foundAny = false;

        for (int c = 0; c < firstRow.getLastCellNum(); c++) {
            String header = cellText(firstRow, c).toLowerCase().trim();
            if (header.isEmpty()) continue;

            if (header.matches("#|no\\.?|num\\.?|number")) {
                numberCol = c; foundAny = true;
            } else if (header.matches("category|section|domain|control\\s*area|area|group")) {
                categoryCol = c; foundAny = true;
            } else if (header.matches("question|requirement|description|control|item")) {
                questionCol = c; foundAny = true;
            }
            // "answer" / "response" columns are silently ignored in Phase 4
        }

        if (!foundAny) {
            // No recognisable headers — use positional fallback:
            // Col 0=number, Col 1=category, Col 2=question  (or Col 0=question if only 1 col)
            int lastCol = firstRow.getLastCellNum();
            if (lastCol >= 3) return new ColumnMap(0, 1, 2, false);
            if (lastCol == 2) return new ColumnMap(-1, 0, 1, false);
            return new ColumnMap(-1, -1, 0, false);
        }

        // If question column still not found, use the widest non-assigned column
        if (questionCol == -1) {
            questionCol = largestUnassignedCol(firstRow, numberCol, categoryCol);
        }

        return new ColumnMap(numberCol, categoryCol, questionCol, true);
    }

    private int largestUnassignedCol(Row headerRow, int... assigned) {
        int best = -1, bestLen = 0;
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            boolean skip = false;
            for (int a : assigned) if (c == a) { skip = true; break; }
            if (skip) continue;
            String v = cellText(headerRow, c);
            if (v.length() > bestLen) { bestLen = v.length(); best = c; }
        }
        return best == -1 ? 0 : best;
    }

    // ── Helpers ───────────────────────────────────────────────────────

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
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
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

    private boolean isMetadataSheet(String name) {
        String lower = name.toLowerCase();
        return lower.contains("instruction") || lower.contains("readme")
                || lower.contains("cover") || lower.contains("index")
                || lower.contains("legend") || lower.contains("glossary");
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}