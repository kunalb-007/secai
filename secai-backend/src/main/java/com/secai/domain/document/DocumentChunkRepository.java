package com.secai.domain.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    /**
     * Core vector similarity search — always org-scoped.
     *
     * Uses pgvector cosine distance operator (<=>).
     * Returns top K chunks where distance < threshold,
     * ordered closest first.
     *
     * @param orgId       current tenant
     * @param embedding   query vector as float[]
     * @param threshold   cosine distance threshold (0.25 ≈ similarity > 0.75)
     * @param limit       max results to return
     */
    @Query(value = """
        SELECT id, organization_id, document_id, section_title, text,
               embedding, chunk_index, token_count, created_at,
               (embedding <=> CAST(:embedding AS vector)) AS distance
        FROM document_chunk
        WHERE organization_id = :orgId
          AND embedding <=> CAST(:embedding AS vector) < :threshold
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> findSimilar(
            @Param("orgId")      UUID    orgId,
            @Param("embedding")  String  embedding,   // passed as '[0.1,0.2,...]' string
            @Param("threshold")  double  threshold,
            @Param("limit")      int     limit
    );

    /**
     * Delete all chunks for a document (used when re-processing or deleting a doc).
     */
    @Modifying
    @Query("DELETE FROM DocumentChunk c WHERE c.documentId = :docId AND c.organizationId = :orgId")
    void deleteByDocumentIdAndOrganizationId(
            @Param("docId")  UUID docId,
            @Param("orgId")  UUID orgId
    );

    long countByDocumentId(UUID documentId);
}