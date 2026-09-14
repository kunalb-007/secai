package com.secai.parsing;

import org.apache.poi.ss.usermodel.*;

/**
 * Detects the questionnaire format from an open Workbook.
 *
 * Detection is deterministic: checks for known structural signals
 * specific to each format before falling back to GENERIC.
 *
 * CAIQ signals (any two sufficient):
 *   - Sheet named "Questionnaire" or "CAIQ" or "CAIQv4" (case-insensitive)
 *   - Header cell containing "Question ID" or "Consensus Assessment"
 *   - Header cell containing "Control ID" and "Question"
 *   - Cell value starting with "CCC." or "GRC." or known CAIQ domain prefixes
 */
public class FormatDetector {

    private static final String[] CAIQ_SHEET_SIGNALS = {
            "questionnaire", "caiq", "caiqv4", "caiq v4", "caiq-v4"
    };

    private static final String[] CAIQ_HEADER_SIGNALS = {
            "question id", "consensus assessment", "caiq", "control id"
    };

    // Known CAIQ v4 domain prefixes — a cell starting with any of these is a strong signal
    private static final String[] CAIQ_DOMAIN_PREFIXES = {
            "A&A.", "AIS.", "BCR.", "CCC.", "CEK.", "DSP.", "GRC.",
            "HRS.", "IAM.", "IPY.", "IVS.", "LOG.", "SEF.", "STA.",
            "TVM.", "UEM."
    };

    public QuestionnaireFormat detect(Workbook workbook) {
        int caiqSignals = 0;

        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            String sheetName = workbook.getSheetAt(s).getSheetName().toLowerCase().trim();

            for (String signal : CAIQ_SHEET_SIGNALS) {
                if (sheetName.contains(signal)) {
                    caiqSignals++;
                    break;
                }
            }
        }

        // Scan first 15 rows of every sheet for CAIQ header signals and domain prefixes
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            int limit = Math.min(15, sheet.getLastRowNum() + 1);

            for (int r = 0; r < limit; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                for (int c = 0; c < row.getLastCellNum(); c++) {
                    String val = cellText(row, c);
                    if (val.isBlank()) continue;

                    String lower = val.toLowerCase();
                    for (String hs : CAIQ_HEADER_SIGNALS) {
                        if (lower.contains(hs)) { caiqSignals++; break; }
                    }

                    for (String prefix : CAIQ_DOMAIN_PREFIXES) {
                        if (val.startsWith(prefix)) { caiqSignals += 2; break; }
                    }
                }
            }

            if (caiqSignals >= 2) return QuestionnaireFormat.CAIQ;
        }

        return caiqSignals >= 2 ? QuestionnaireFormat.CAIQ : QuestionnaireFormat.GENERIC;
    }

    private String cellText(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> "";
        };
    }
}