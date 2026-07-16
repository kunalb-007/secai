package com.secai.controller;

import com.secai.config.TenantContext;
import com.secai.domain.document.Document;
import com.secai.domain.document.DocumentChunk;
import com.secai.domain.document.DocumentChunkRepository;
import com.secai.domain.document.DocumentRepository;
import com.secai.dto.document.ChunkSourceResponse;
import com.secai.exception.ForbiddenException;
import com.secai.exception.NotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * GET /api/chunks/{id}
 * Returns the full text of a document chunk for the "View Source" feature.
 * Always org-scoped — a user cannot read chunks from another tenant.
 */
@RestController
@RequestMapping("/api/chunks")
public class ChunkController {

    private final DocumentChunkRepository chunkRepo;
    private final DocumentRepository      documentRepo;

    public ChunkController(
            DocumentChunkRepository chunkRepo,
            DocumentRepository      documentRepo
    ) {
        this.chunkRepo    = chunkRepo;
        this.documentRepo = documentRepo;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChunkSourceResponse> getChunk(@PathVariable UUID id) {
        UUID orgId = TenantContext.get();

        DocumentChunk chunk = chunkRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Chunk not found"));

        // Tenant isolation check — never expose another org's document text
        if (!orgId.equals(chunk.getOrganizationId())) {
            throw new ForbiddenException("Access denied");
        }

        Document doc = documentRepo.findByIdAndOrganizationId(chunk.getDocumentId(), orgId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        return ResponseEntity.ok(new ChunkSourceResponse(
                chunk.getId(),
                doc.getId(),
                doc.getFilename(),
                chunk.getSectionTitle(),
                chunk.getText()
        ));
    }
}