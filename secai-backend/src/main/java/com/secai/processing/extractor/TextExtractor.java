package com.secai.processing.extractor;

/**
 * Extracts raw text from a file on disk.
 * Each implementation handles one file type.
 */
public interface TextExtractor {
    /**
     * @param filePath absolute path to the file on the local filesystem
     * @return extracted plain text (UTF-8)
     * @throws TextExtractionException if extraction fails
     */
    String extract(String filePath) throws TextExtractionException;
}