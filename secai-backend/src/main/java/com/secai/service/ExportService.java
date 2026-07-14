package com.secai.service;

import com.secai.config.TenantContext;
import com.secai.domain.questionnaire.*;
import com.secai.exception.NotFoundException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Phase 7 — Export completed questionnaire answers as .xlsx.
 *
 * Column layout:
 *   A  Question #
 *   B  Category
 *   C  Question
 *   D  Answer          ← manual_answer if EDITED, else ai_answer
 *   E  Evidence
 *   F  Status
 *
 * Styling decisions:
 *   - Header row: dark navy fill, white bold text, 12pt
 *   - APPROVED rows: light green left-border accent
 *   - EDITED rows:   light blue left-border accent
 *   - REJECTED rows: light red, italic "Requires manual answer"
 *   - PENDING rows:  yellow, italic "Not yet answered"
 *   - Score column intentionally excluded from export —
 *     customers don't need to see confidence internals
 *
 * Columns C and D are text-wrapped and set to wider widths
 * so long questions / answers are readable without manual resize.
 */
@Service
public class ExportService {

    private final QuestionnaireRepository questionnaireRepo;
    private final QuestionRepository      questionRepo;

    // Colour palette (XSSF uses RGB bytes)
    private static final byte[] HEADER_BG   = hex("#1a3a5c"); // deep navy
    private static final byte[] APPROVED_BG = hex("#f0fff4"); // very light green
    private static final byte[] EDITED_BG   = hex("#e6f4ff"); // very light blue
    private static final byte[] REJECTED_BG = hex("#fff2f0"); // very light red
    private static final byte[] PENDING_BG  = hex("#fffbe6"); // very light yellow

    public ExportService(
            QuestionnaireRepository questionnaireRepo,
            QuestionRepository      questionRepo
    ) {
        this.questionnaireRepo = questionnaireRepo;
        this.questionRepo      = questionRepo;
    }

    /**
     * Builds and returns the xlsx bytes for the given questionnaire.
     * Validates org ownership before proceeding.
     *
     * @param questionnaireId target questionnaire
     * @return raw bytes of the .xlsx file ready to stream to the browser
     */
    public byte[] export(UUID questionnaireId) throws IOException {
        UUID orgId = TenantContext.get();

        Questionnaire questionnaire = questionnaireRepo
                .findByIdAndOrganizationId(questionnaireId, orgId)
                .orElseThrow(() -> new NotFoundException("Questionnaire not found"));

        List<Question> questions =
                questionRepo.findByQuestionnaireIdAndOrganizationIdOrderBySortOrder(
                        questionnaireId, orgId
                );

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Answers");

            // ── Cell styles ───────────────────────────────────────────────────
            CellStyle headerStyle   = buildHeaderStyle(wb);
            CellStyle approvedStyle = buildRowStyle(wb, APPROVED_BG, false, IndexedColors.GREEN);
            CellStyle editedStyle   = buildRowStyle(wb, EDITED_BG,   false, IndexedColors.CORNFLOWER_BLUE);
            CellStyle rejectedStyle = buildRowStyle(wb, REJECTED_BG, true,  IndexedColors.RED);
            CellStyle pendingStyle  = buildRowStyle(wb, PENDING_BG,  true,  IndexedColors.GOLD);
            CellStyle defaultStyle  = buildRowStyle(wb, null,        false, null);

            // ── Column widths (POI units: 1/256th of character width) ─────────
            sheet.setColumnWidth(0,  8  * 256);   // A: #
            sheet.setColumnWidth(1,  20 * 256);   // B: Category
            sheet.setColumnWidth(2,  50 * 256);   // C: Question
            sheet.setColumnWidth(3,  60 * 256);   // D: Answer
            sheet.setColumnWidth(4,  35 * 256);   // E: Evidence
            sheet.setColumnWidth(5,  14 * 256);   // F: Status

            // ── Freeze top row ────────────────────────────────────────────────
            sheet.createFreezePane(0, 1);

            // ── Auto-filter on all columns ────────────────────────────────────
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, 5));

            // ── Header row ────────────────────────────────────────────────────
            String[] headers = { "Question #", "Category", "Question", "Answer", "Evidence", "Status" };
            Row headerRow = sheet.createRow(0);
            headerRow.setHeightInPoints(20);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // ── Data rows ─────────────────────────────────────────────────────
            int rowNum = 1;
            for (Question q : questions) {

                // Determine the answer to export
                String answer = resolveAnswer(q);

                // Pick row style based on question status
                CellStyle rowStyle = switch (q.getStatus()) {
                    case APPROVED -> approvedStyle;
                    case EDITED   -> editedStyle;
                    case REJECTED -> rejectedStyle;
                    case PENDING  -> pendingStyle;
                    default       -> defaultStyle;   // GENERATED
                };

                Row row = sheet.createRow(rowNum++);
                row.setHeightInPoints(40);  // taller rows for wrapped text

                createCell(row, 0, q.getQuestionNumber() != null ? q.getQuestionNumber() : "", rowStyle);
                createCell(row, 1, q.getCategory()       != null ? q.getCategory()       : "", rowStyle);
                createCell(row, 2, q.getQuestionText()   != null ? q.getQuestionText()   : "", rowStyle);
                createCell(row, 3, answer,                                                       rowStyle);
                createCell(row, 4, q.getEvidence()       != null ? q.getEvidence()       : "", rowStyle);
                createCell(row, 5, q.getStatus().name(),                                        rowStyle);
            }

            // ── Write to bytes ─────────────────────────────────────────────────
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ── Answer resolution ─────────────────────────────────────────────────────

    /**
     * For EDITED or REJECTED: use manual_answer (the human's version).
     * For everything else (GENERATED, APPROVED, PENDING): use ai_answer.
     * If neither exists, return a placeholder.
     */
    private String resolveAnswer(Question q) {
        if ((q.getStatus() == QuestionStatus.EDITED || q.getStatus() == QuestionStatus.REJECTED)
                && q.getManualAnswer() != null && !q.getManualAnswer().isBlank()) {
            return q.getManualAnswer();
        }
        if (q.getAiAnswer() != null && !q.getAiAnswer().isBlank()) {
            return q.getAiAnswer();
        }
        return "";
    }

    // ── Style builders ────────────────────────────────────────────────────────

    private CellStyle buildHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setFontName("Arial");
        font.setFontHeightInPoints((short) 11);
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(HEADER_BG, new DefaultIndexedColorMap()));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBottomBorderColor(IndexedColors.WHITE.getIndex());
        return style;
    }

    private CellStyle buildRowStyle(
            XSSFWorkbook wb,
            byte[]         bgRgb,
            boolean        italic,
            IndexedColors  leftBorderColor
    ) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFFont font = wb.createFont();
        font.setFontName("Arial");
        font.setFontHeightInPoints((short) 10);
        font.setItalic(italic);
        style.setFont(font);

        if (bgRgb != null) {
            style.setFillForegroundColor(new XSSFColor(bgRgb, new DefaultIndexedColorMap()));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        if (leftBorderColor != null) {
            style.setBorderLeft(BorderStyle.MEDIUM);
            style.setLeftBorderColor(leftBorderColor.getIndex());
        }

        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());

        return style;
    }

    private void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    // ── Colour helper ─────────────────────────────────────────────────────────

    private static byte[] hex(String hex) {
        hex = hex.replace("#", "");
        return new byte[] {
                (byte) Integer.parseInt(hex.substring(0, 2), 16),
                (byte) Integer.parseInt(hex.substring(2, 4), 16),
                (byte) Integer.parseInt(hex.substring(4, 6), 16)
        };
    }
}