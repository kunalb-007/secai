package com.secai.processing.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        try {
            byte[] fileBytes = Files.readAllBytes(path);
            String fileName = path.getFileName().toString();

            // Build multipart form body manually
            String boundary = "----SecAIBoundary" + System.currentTimeMillis();
            byte[] multipartBody = buildMultipartBody(boundary, fileName, fileBytes);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(markerServiceUrl + "/convert"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofMinutes(5))   // large PDFs can take a while
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                    .build();

            log.info("Sending PDF to Marker service: {}", fileName);
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new TextExtractionException(
                        "Marker service returned HTTP " + response.statusCode()
                                + " for file: " + fileName
                );
            }

            // Marker returns: { "markdown": "# Title\n\nContent...", "pages": 12 }
            JsonNode json = objectMapper.readTree(response.body());
            String markdown = json.path("markdown").asText("");

            if (markdown.isBlank()) {
                throw new TextExtractionException(
                        "Marker returned empty text for file: " + fileName
                                + ". The PDF may be scanned/image-only or password-protected."
                );
            }

            log.info("Marker extracted {} chars from {}", markdown.length(), fileName);
            return markdown;

        } catch (TextExtractionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TextExtractionException("PDF extraction interrupted", e);
        } catch (Exception e) {
            throw new TextExtractionException(
                    "PDF extraction failed: " + e.getMessage(), e
            );
        }
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
}