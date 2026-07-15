package com.secai.service;

import com.secai.config.TenantContext;
import com.secai.domain.document.Document;
import com.secai.domain.document.DocumentChunkRepository;
import com.secai.domain.document.DocumentRepository;
import com.secai.domain.document.DocumentStatus;
import com.secai.dto.document.DocumentListResponse;
import com.secai.dto.document.DocumentStatusResponse;
import com.secai.dto.document.DocumentUploadResponse;
import com.secai.exception.ForbiddenException;
import com.secai.exception.NotFoundException;
import com.secai.processing.DocumentProcessingWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class DocumentService {

    // Allowed MIME types for security documents
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",  // DOCX
            "text/plain"
    );

    // Allowed file extensions (double-check in case browser sends wrong MIME)
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".txt"
    );

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final StorageService storageService;
    private final DocumentProcessingWorker processingWorker;

    public DocumentService(
            DocumentRepository documentRepository,
            StorageService storageService,
            DocumentProcessingWorker processingWorker,
            DocumentChunkRepository documentChunkRepository
    ) {
        this.documentRepository = documentRepository;
        this.storageService     = storageService;
        this.processingWorker   = processingWorker;
        this.documentChunkRepository = documentChunkRepository;
    }

    /**
     * Upload a security document.
     * Validates type, saves to S3, creates DB record with PENDING status.
     */
    public DocumentUploadResponse upload(MultipartFile file) {
        UUID orgId = TenantContext.get();

        log.info("Document upload received: {}",
                file.getOriginalFilename());

        // --- Validation ---
        validateFile(file);

        // --- Create DB record first (get UUID for S3 path) ---
        Document doc = documentRepository.save(
                Document.builder()
                        .organizationId(orgId)
                        .filename(sanitizeFilename(file.getOriginalFilename()))
                        .contentType(file.getContentType())
                        .status(DocumentStatus.PENDING)
                        .build()
        );

        log.info("Created document {} with status {}",
                doc.getId(),
                doc.getStatus());

        // --- Upload to S3 ---
        try {
            String storagePath = storageService.upload(file, orgId.toString(), doc.getId().toString());
            doc.setStoragePath(storagePath);
            documentRepository.save(doc);

            log.info("Document {} stored successfully",
                    doc.getId());

            // ── PHASE 3 ADDITION: Trigger async processing ──────────────────────
            processingWorker.processAsync(doc.getId());

            log.info("Starting asynchronous processing for document {}",
                    doc.getId());
            // ────────────────────────────────────────────────────────────────────

        } catch (Exception e) {
            // If S3 upload fails, mark document FAILED
            doc.setStatus(DocumentStatus.FAILED);
            documentRepository.save(doc);
            throw new RuntimeException("File storage failed. Please try again.", e);
        }

        log.info("Document upload completed: {}",
                doc.getId());

        return new DocumentUploadResponse(
                doc.getId(),
                doc.getFilename(),
                doc.getStatus(),
                "Document uploaded successfully. Processing will begin shortly."
        );
    }

    /**
     * List all documents for the current org.
     */
    public List<DocumentListResponse> listForCurrentOrg() {
        UUID orgId = TenantContext.get();
        return documentRepository.findByOrganizationIdOrderByUploadedAtDesc(orgId)
                .stream()
                .map(d -> new DocumentListResponse(
                        d.getId(),
                        d.getFilename(),
                        d.getStatus(),
                        d.getErrorMessage(),
                        d.getUploadedAt(),
                        d.getProcessedAt()
                ))
                .toList();
    }

    /**
     * Get a single document — verifies org ownership.
     */
    public DocumentListResponse getById(UUID documentId) {
        UUID orgId = TenantContext.get();
        Document doc = documentRepository.findByIdAndOrganizationId(documentId, orgId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        return new DocumentListResponse(
                doc.getId(),
                doc.getFilename(),
                doc.getStatus(),
                doc.getErrorMessage(),
                doc.getUploadedAt(),
                doc.getProcessedAt()
        );
    }

    /**
     * Delete a document — verifies org ownership before deletion.
     */
    public void delete(UUID documentId) {
        UUID orgId = TenantContext.get();
        Document doc = documentRepository.findByIdAndOrganizationId(documentId, orgId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        if (doc.getStoragePath() != null) {
            storageService.delete(doc.getStoragePath());
        }
        documentRepository.delete(doc);

        log.info("Deleted document {}",
                documentId);
    }

    // ---- Private helpers ----

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > 50 * 1024 * 1024) {  // 50MB
            throw new IllegalArgumentException("File size exceeds 50MB limit");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename is missing");
        }

        String lower = filename.toLowerCase();
        boolean validExtension = ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!validExtension) {
            throw new IllegalArgumentException(
                    "Unsupported file type. Allowed: PDF, DOCX, TXT. DOC (old Word) is not supported."
            );
        }

        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Invalid content type: " + contentType
            );
        }
    }

    public DocumentStatusResponse getStatus(UUID documentId) {
        UUID orgId = TenantContext.get();
        Document doc = documentRepository.findByIdAndOrganizationId(documentId, orgId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        Long chunkCount = null;
        if (doc.getStatus() == DocumentStatus.READY) {
            chunkCount = documentChunkRepository.countByDocumentId(documentId);
        }

        return new DocumentStatusResponse(
                doc.getId(),
                doc.getStatus(),
                doc.getErrorMessage(),
                chunkCount
        );
    }



    private String sanitizeFilename(String filename) {
        if (filename == null) return "unnamed";
        // Remove any path traversal attempts
        return filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}