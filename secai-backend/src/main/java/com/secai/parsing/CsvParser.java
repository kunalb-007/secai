package com.secai.parsing;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses CSV questionnaires using OpenCSV.
 *
 * Same column-detection logic as XLSX: looks for headers that match
 * known patterns for number, category, question columns.
 * Falls back to positional if no headers found.
 */
@Component
public class CsvParser implements QuestionnaireParser {

    @Override
    public ParseResult parse(MultipartFile file) throws ParseException {
        List<ParsedQuestion> questions = new ArrayList<>();
        int totalRowsAttempted = 0;

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String[] headers = reader.readNext();
            if (headers == null) {
                throw new ParseException("CSV file is empty");
            }

            // Remove UTF-8 BOM if present
            if (headers.length > 0 && headers[0] != null) {
                headers[0] = headers[0].replace("\uFEFF", "");
            }

            // Detect column positions from header row
            int numberCol = -1, categoryCol = -1, questionCol = -1;
            boolean hasRecognisedHeaders = false;

            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].toLowerCase().trim();
                if (h.matches("#|no\\.?|num\\.?|number|id")) {
                    numberCol = i; hasRecognisedHeaders = true;
                } else if (h.matches("category|section|domain|area|group|control\\s*area")) {
                    categoryCol = i; hasRecognisedHeaders = true;
                } else if (h.matches("question|requirement|description|control|item|text")) {
                    questionCol = i; hasRecognisedHeaders = true;
                }
            }

            // Positional fallback
            if (!hasRecognisedHeaders) {
                if (headers.length >= 3) { numberCol = 0; categoryCol = 1; questionCol = 2; }
                else if (headers.length == 2) { numberCol = 0; questionCol = 1; }
                else { questionCol = 0; }

                // First row was actually data, not headers — re-process it
                String q = questionCol < headers.length ? headers[questionCol] : "";
                if (!q.isBlank() && q.length() >= 5) {
                    questions.add(new ParsedQuestion(
                            numberCol >= 0 && numberCol < headers.length ? emptyToNull(headers[numberCol]) : null,
                            q.trim(),
                            categoryCol >= 0 && categoryCol < headers.length ? emptyToNull(headers[categoryCol]) : null,
                            questions.size()
                    ));
                }
                totalRowsAttempted++;
            }

            // If question column still not found, use last column (usually longest)
            if (questionCol == -1) questionCol = headers.length - 1;

            // Process remaining rows
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (isRowEmpty(row)) continue;
                totalRowsAttempted++;

                String number   = numberCol   >= 0 && numberCol   < row.length ? row[numberCol].trim()   : null;
                String category = categoryCol >= 0 && categoryCol < row.length ? row[categoryCol].trim() : null;
                String question = questionCol >= 0 && questionCol < row.length ? row[questionCol].trim() : null;

                if (question == null || question.isBlank() || question.trim().length() < 5) {
                    continue;
                }

                questions.add(new ParsedQuestion(
                        emptyToNull(number),
                        question,
                        emptyToNull(category),
                        questions.size()
                ));
            }

        } catch (ParseException e) {
            throw e;
        } catch (CsvValidationException e) {
            throw new ParseException("Invalid CSV format: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ParseException("Failed to parse CSV file: " + e.getMessage(), e);
        }

        return ParseResult.of(questions, totalRowsAttempted);
    }

    private boolean isRowEmpty(String[] row) {
        for (String cell : row) if (!cell.isBlank()) return false;
        return true;
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}