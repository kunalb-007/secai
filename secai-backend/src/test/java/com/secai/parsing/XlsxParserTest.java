package com.secai.parsing;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.*;

class XlsxParserTest {

    private final XlsxParser parser = new XlsxParser();

    @Test
    void parse_withHeaders_detectsCorrectColumns() throws Exception {
        // Build a minimal XLSX in memory
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Security");

        // Header row
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("#");
        header.createCell(1).setCellValue("Category");
        header.createCell(2).setCellValue("Question");

        // Data rows
        Row r1 = sheet.createRow(1);
        r1.createCell(0).setCellValue("1.1");
        r1.createCell(1).setCellValue("Data Security");
        r1.createCell(2).setCellValue("Do you encrypt data at rest?");

        Row r2 = sheet.createRow(2);
        r2.createCell(0).setCellValue("1.2");
        r2.createCell(1).setCellValue("Data Security");
        r2.createCell(2).setCellValue("What encryption algorithm do you use?");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        wb.write(baos);
        wb.close();

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                baos.toByteArray()
        );

        ParseResult result = parser.parse(file);

        assertThat(result.questions()).hasSize(2);
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.lowConfidence()).isFalse();

        ParsedQuestion q1 = result.questions().get(0);
        assertThat(q1.questionNumber()).isEqualTo("1.1");
        assertThat(q1.category()).isEqualTo("Data Security");
        assertThat(q1.questionText()).isEqualTo("Do you encrypt data at rest?");
    }

    @Test
    void parse_noHeaders_usesPositionalFallback() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Sheet1");

        // No header row, data starts at row 0
        Row r1 = sheet.createRow(0);
        r1.createCell(0).setCellValue("1");
        r1.createCell(1).setCellValue("Access Control");
        r1.createCell(2).setCellValue("Is MFA enforced for all admin accounts?");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        wb.write(baos);
        wb.close();

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                baos.toByteArray()
        );

        ParseResult result = parser.parse(file);

        // Should find 1 question using positional column mapping
        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).questionText())
                .isEqualTo("Is MFA enforced for all admin accounts?");
    }
}