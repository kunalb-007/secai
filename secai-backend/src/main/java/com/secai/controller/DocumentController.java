package com.secai.controller;

import com.secai.dto.document.DocumentListResponse;
import com.secai.dto.document.DocumentStatusResponse;
import com.secai.dto.document.DocumentUploadResponse;
import com.secai.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * POST /api/documents
     * Upload a new security document (PDF, DOCX, TXT)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> upload(
            @RequestParam("file") MultipartFile file
    ) {
        DocumentUploadResponse response = documentService.upload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/documents
     * List all documents for the authenticated org
     */
    @GetMapping
    public ResponseEntity<List<DocumentListResponse>> list() {
        return ResponseEntity.ok(documentService.listForCurrentOrg());
    }

    /**
     * GET /api/documents/{id}
     * Get a single document by ID (org-scoped)
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentListResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(documentService.getById(id));
    }

    /**
     * DELETE /api/documents/{id}
     * Delete a document (org-scoped)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/documents/{id}/status
     * Lightweight polling endpoint — returns just the status + error message.
     * Frontend polls this every 5 seconds while document is PENDING/PROCESSING.
     */
    @GetMapping("/{id}/status")

    public ResponseEntity<DocumentStatusResponse> getStatus(@PathVariable UUID id) {
        return ResponseEntity.ok(documentService.getStatus(id));
    }
}