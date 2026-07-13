package com.secai.processing;

import com.secai.domain.document.*;
import com.secai.processing.extractor.*;
import com.secai.service.EmbeddingService;
import com.secai.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Orchestrates the full document processing pipeline (steps 1-7).
 *
 * Called by the async worker. All steps run synchronously within this service;
 * async dispatch happens one level up in DocumentProcessingWorker.
 *
 * NOT @Async itself — that lives in the worker. This makes unit testing easier.
 */
@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final DocumentRepository      documentRepo;
    private final DocumentChunkRepository chunkRepo;
    private final StorageService          storageService;
    private final TextExtractorFactory    extractorFactory;
    private final TextCleaner             textCleaner;
    private final TextChunker             textChunker;
    private final EmbeddingService        embeddingService;

    public DocumentProcessingService(
            DocumentRepository      documentRepo,
            DocumentChunkRepository chunkRepo,
            StorageService          storageService,
            TextExtractorFactory    extractorFactory,
            TextCleaner             textCleaner,
            TextChunker             textChunker,
            EmbeddingService        embeddingService
    ) {
        this.documentRepo     = documentRepo;
        this.chunkRepo        = chunkRepo;
        this.storageService   = storageService;
        this.extractorFactory = extractorFactory;
        this.textCleaner      = textCleaner;
        this.textChunker      = textChunker;
        this.embeddingService = embeddingService;
    }

    /**
     * Process one document through all 7 pipeline steps.
     *
     * @param documentId the document to process
     * @throws Exception if processing fails (caller handles retry and FAILED status)
     */
    public void process(UUID documentId) throws Exception {
        Document doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        log.info("[{}] Starting processing pipeline for: {}", documentId, doc.getFilename());

        // Guard: skip if already processed (idempotent)
        if (doc.getStatus() == DocumentStatus.READY) {
            log.info("[{}] Already READY, skipping", documentId);
            return;
        }

        // Mark as PROCESSING
        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepo.save(doc);

        Path tempFile = null;
        try {
            // ── Step 1: Download file from S3 to a temp location ──────────────
            log.info("[{}] Step 1: Downloading from S3", documentId);
            tempFile = storageService.downloadToTemp(doc.getStoragePath(), doc.getFilename());

            // ── Step 1b: Extract text ──────────────────────────────────────────
            TextExtractor extractor = extractorFactory.forContentType(
                    doc.getContentType(), doc.getFilename()
            );
            String rawText = extractor.extract(tempFile.toString());
            log.info("[{}] Extracted {} chars", documentId, rawText.length());

            // ── Step 2: Store extracted text to S3 (for debugging) ─────────────
            log.info("[{}] Step 2: Storing extracted text to S3", documentId);
            String extractedPath = storageService.uploadText(
                    rawText,
                    doc.getOrganizationId().toString(),
                    documentId.toString()
            );

            // ── Step 3: Clean text ─────────────────────────────────────────────
            log.info("[{}] Step 3: Cleaning text", documentId);
            String cleanedText = textCleaner.clean(rawText);
            log.info("[{}] After cleaning: {} chars", documentId, cleanedText.length());

            // ── Step 4: Chunk text ─────────────────────────────────────────────
            log.info("[{}] Step 4: Chunking text", documentId);
            List<TextChunker.Chunk> chunks = textChunker.chunk(cleanedText);
            log.info("[{}] Created {} chunks", documentId, chunks.size());

            if (chunks.isEmpty()) {
                throw new IllegalStateException(
                        "Document produced zero chunks after cleaning. "
                                + "The file may be empty or contain only non-text content."
                );
            }

            // ── Step 5: Generate embeddings ────────────────────────────────────
            log.info("[{}] Step 5: Generating embeddings for {} chunks", documentId, chunks.size());
            List<String> chunkTexts = chunks.stream()
                    .map(TextChunker.Chunk::text)
                    .toList();
            List<float[]> embeddings = embeddingService.embedAll(chunkTexts);

            // ── Step 6: Persist chunks with embeddings ─────────────────────────
            log.info("[{}] Step 6: Persisting {} chunks to DB", documentId, chunks.size());

            // Delete any existing chunks (safe for re-processing)
            chunkRepo.deleteByDocumentIdAndOrganizationId(documentId, doc.getOrganizationId());

            List<DocumentChunk> chunkEntities = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                TextChunker.Chunk c = chunks.get(i);
                chunkEntities.add(
                        DocumentChunk.builder()
                                .organizationId(doc.getOrganizationId())
                                .documentId(documentId)
                                .sectionTitle(c.sectionTitle())
                                .text(c.text())
                                .embedding(embeddings.get(i))
                                .chunkIndex(c.chunkIndex())
                                .tokenCount(c.tokenCount())
                                .build()
                );
            }

            // Save in batches of 50 to avoid massive INSERT statements
            saveBatched(chunkEntities, 50);

            // ── Step 7: Mark document READY ────────────────────────────────────
            log.info("[{}] Step 7: Marking READY", documentId);
            doc.setStatus(DocumentStatus.READY);
            doc.setProcessedAt(OffsetDateTime.now());
            doc.setErrorMessage(null);
            documentRepo.save(doc);

            log.info("[{}] ✓ Processing complete. {} chunks indexed.", documentId, chunks.size());

        } catch (Exception e) {
            log.error("[{}] Processing failed: {}", documentId, e.getMessage(), e);
            // Re-throw — the caller (worker) sets FAILED status and handles retry
            throw e;
        } finally {
            // Always clean up the temp file
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); }
                catch (Exception ignored) {}
            }
        }
    }

    @Transactional
    private void saveBatched(List<DocumentChunk> chunks, int batchSize) {
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<DocumentChunk> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            chunkRepo.saveAll(batch);
        }
    }
}