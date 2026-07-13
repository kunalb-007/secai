package com.secai.parsing;

import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.*;

class DocxParserTest {

    private final DocxParser parser = new DocxParser();

    @Test
    void parse_numberedParagraphs_extractsQuestions() throws Exception {
        XWPFDocument doc = new XWPFDocument();

        // Section header
        XWPFParagraph heading = doc.createParagraph();
        heading.setStyle("Heading1");
        heading.createRun().setText("Access Control");

        // Numbered questions
        String[] questionLines = {
                "1. Do you enforce multi-factor authentication for all users?",
                "2. How do you manage privileged access to production systems?",
                "3. What is your process for revoking access when employees leave?",
        };
        for (String line : questionLines) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText(line);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.write(baos);
        doc.close();

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                baos.toByteArray()
        );

        ParseResult result = parser.parse(file);

        assertThat(result.questions()).hasSize(3);
        assertThat(result.questions().get(0).category()).isEqualTo("Access Control");
        assertThat(result.questions().get(0).questionNumber()).isEqualTo("1.");
        assertThat(result.questions().get(0).questionText())
                .isEqualTo("Do you enforce multi-factor authentication for all users?");
    }

    @Test
    void parse_questionMarkFallback_detectsQuestions() throws Exception {
        XWPFDocument doc = new XWPFDocument();
        doc.createParagraph().createRun()
                .setText("Does your organization maintain a business continuity plan?");
        doc.createParagraph().createRun()
                .setText("Is the plan tested at least annually?");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.write(baos);
        doc.close();

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                baos.toByteArray()
        );

        ParseResult result = parser.parse(file);
        assertThat(result.questions()).hasSizeGreaterThanOrEqualTo(1);
    }
}