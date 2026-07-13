package com.secai.processing.extractor;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

@Component
public class TxtTextExtractor implements TextExtractor {

    @Override
    public String extract(String filePath) throws TextExtractionException {
        try {
            String content = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new TextExtractionException("TXT file is empty: " + filePath);
            }
            return content;
        } catch (TextExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new TextExtractionException(
                    "Failed to read TXT file " + filePath + ": " + e.getMessage(), e
            );
        }
    }
}