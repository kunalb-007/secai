package com.secai.controller;

import com.secai.config.TenantContext;
import com.secai.domain.document.Document;
import com.secai.domain.document.DocumentChunk;
import com.secai.domain.document.DocumentChunkRepository;
import com.secai.domain.document.DocumentRepository;
import com.secai.dto.document.ChunkSourceResponse;
import com.secai.exception.NotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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

        DocumentChunk chunk =
                chunkRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NotFoundException("Chunk not found"));

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