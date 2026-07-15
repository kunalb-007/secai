package com.secai.processing.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.apache.pdfbox.Loader.loadPDF;

@Component
public class PdfTextExtractor implements TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    private final String markerServiceUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PdfTextExtractor(
            @Value("${app.processing.marker-service-url}") String markerServiceUrl
    ) {
        this.markerServiceUrl = markerServiceUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Sends the PDF file to the Marker HTTP service and returns extracted Markdown text.
     *
     * Marker preserves document structure (headings, sections) as Markdown (## Header),
     * which our chunker then uses to split at section boundaries.
     */
    @Override
    public String extract(String filePath) throws TextExtractionException {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            throw new TextExtractionException("PDF file not found: " + filePath);
        }

        // ------------------------------------------------------------------
        // FAST PATH
        // Try Apache PDFBox first.
        // ------------------------------------------------------------------

        try {
            String pdfBoxText = extractWithPdfBox(path);

            if (pdfBoxText != null) {

                String cleaned = pdfBoxText.trim();

                // Digital PDFs normally contain thousands of characters.
                // If we extracted enough text, skip expensive OCR.
                if (cleaned.length() > 200) {

                    log.info(
                            "PDFBox extracted {} chars. Using PDFBox output.",
                            cleaned.length()
                    );

                    return cleaned;
                }

                log.info(
                        "PDFBox extracted only {} chars. Falling back to Marker OCR.",
                        cleaned.length()
                );
            }

        } catch (Exception e) {

            log.warn(
                    "PDFBox extraction failed. Falling back to Marker. {}",
                    e.getMessage()
            );
        }

        // ------------------------------------------------------------------
        // FALLBACK
        // Marker OCR
        // ------------------------------------------------------------------

        return extractWithMarker(path);
    }

    /**
     * Builds a minimal multipart/form-data body with a single "file" field.
     */
    private byte[] buildMultipartBody(String boundary, String filename, byte[] fileBytes)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String CRLF = "\r\n";

        // Part header
        String partHeader = "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + CRLF
                + "Content-Type: application/pdf" + CRLF
                + CRLF;
        out.write(partHeader.getBytes());
        out.write(fileBytes);
        out.write(CRLF.getBytes());

        // Closing boundary
        String closing = "--" + boundary + "--" + CRLF;
        out.write(closing.getBytes());

        return out.toByteArray();
    }

    private String extractWithPdfBox(Path path) throws IOException {

        try (PDDocument document =
                     loadPDF(path.toFile())) {

            org.apache.pdfbox.text.PDFTextStripper stripper =
                    new org.apache.pdfbox.text.PDFTextStripper();

            stripper.setSortByPosition(true);

            return stripper.getText(document);
        }
    }

    private String extractWithMarker(Path path) throws TextExtractionException {

        // First check if Marker is reachable — fail fast rather than blocking 15 min
        if (!isMarkerAvailable()) {
            throw new TextExtractionException(
                    "Marker OCR service is not available. " +
                            "This PDF may be image-only and cannot be processed without Marker."
            );
        }

        try {
            byte[] fileBytes = Files.readAllBytes(path);
            String fileName  = path.getFileName().toString();
            String boundary  = "----SecAIBoundary" + System.currentTimeMillis();
            byte[] multipartBody = buildMultipartBody(boundary, fileName, fileBytes);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(markerServiceUrl + "/convert"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofMinutes(5))   // reduced from 15 — fail faster for demo
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                    .build();

            log.info("Sending PDF to Marker OCR: {}", fileName);

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new TextExtractionException(
                        "Marker service returned HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String markdown = json.path("markdown").asText("");

            if (markdown.isBlank()) {
                throw new TextExtractionException("Marker returned empty text.");
            }

            log.info("Marker extracted {} chars", markdown.length());
            return markdown;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TextExtractionException("Marker OCR interrupted", e);
        } catch (TextExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new TextExtractionException("Marker OCR failed: " + e.getMessage(), e);
        }
    }

    /**
     * Quick availability check — 3 second timeout.
     * Prevents thread-pool starvation when Marker isn't running.
     */
    private boolean isMarkerAvailable() {
        try {
            HttpRequest ping = HttpRequest.newBuilder()
                    .uri(URI.create(markerServiceUrl + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(
                    ping, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() < 500;
        } catch (Exception e) {
            log.warn("Marker service not reachable: {}", e.getMessage());
            return false;
        }
    }
}