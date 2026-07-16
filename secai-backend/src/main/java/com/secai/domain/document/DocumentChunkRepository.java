package com.secai.domain.document;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Phase 5 UPDATE — replaces the Phase 3 version.
 *
 * Key change: findSimilar() now returns results via a @Query that exposes
 * the distance alias. However, because Spring Data JPA cannot directly map
 * native query columns to @Transient fields, we use a two-step approach:
 *
 *   Step A: Execute the native query → get List<Object[]> (raw rows)
 *   Step B: Map each Object[] back into a DocumentChunk with distance set
 *
 * This is done in DocumentChunkRepositoryCustom / the service layer.
 *
 * ALTERNATIVELY — and this is the simpler path we use here — we add a
 * separate native query that returns Object[] and let AnswerGenerationService
 * do the mapping. See findSimilarRaw() below.
 *
 * IMPORTANT: Replace the existing DocumentChunkRepository.java with this version.
 */
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    /**
     * Phase 5 core query — vector similarity search, always org-scoped.
     *
     * Returns raw Object[] rows in this column order:
     *   [0] id              UUID
     *   [1] organization_id UUID
     *   [2] document_id     UUID
     *   [3] section_title   String  (nullable)
     *   [4] text            String
     *   [5] chunk_index     Integer
     *   [6] token_count     Integer (nullable)
     *   [7] created_at      OffsetDateTime
     *   [8] distance        Double  ← cosine distance from pgvector <=>
     *
     * The embedding column is intentionally excluded — it is large (1536 floats)
     * and not needed after retrieval.
     *
     * @param orgId      current tenant — NEVER query without this
     * @param embedding  query vector as pgvector string '[0.1,0.2,...]'
     * @param threshold  cosine distance threshold (0.25 ≈ similarity > 0.75)
     * @param limit      max results
     */
    @Query(value = """
        SELECT
            id,
            organization_id,
            document_id,
            section_title,
            text,
            chunk_index,
            token_count,
            created_at,
            (embedding <=> CAST(:embedding AS vector)) AS distance
        FROM document_chunk
        WHERE organization_id = :orgId
          AND embedding <=> CAST(:embedding AS vector) < :threshold
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findSimilarRaw(
            @Param("orgId")      UUID   orgId,
            @Param("embedding")  String embedding,
            @Param("threshold")  double threshold,
            @Param("limit")      int    limit
    );

    /**
     * Convenience wrapper — maps Object[] rows into DocumentChunk entities
     * with the distance field set so getSimilarity() works correctly.
     *
     * Called by AnswerGenerationService — this is the method the service uses.
     */
    default List<DocumentChunk> findSimilar(
            UUID   orgId,
            String embedding,
            double threshold,
            int    limit
    ) {
        List<Object[]> rows = findSimilarRaw(orgId, embedding, threshold, limit);
        return rows.stream()
                .map(row -> {
                    DocumentChunk chunk = new DocumentChunk();
                    chunk.setId(row[0] != null ? java.util.UUID.fromString(row[0].toString()) : null);
                    chunk.setOrganizationId(row[1] != null ? java.util.UUID.fromString(row[1].toString()) : null);
                    chunk.setDocumentId(row[2] != null ? java.util.UUID.fromString(row[2].toString()) : null);
                    chunk.setSectionTitle(row[3] != null ? row[3].toString() : null);
                    chunk.setText(row[4] != null ? row[4].toString() : "");
                    chunk.setChunkIndex(row[5] != null ? ((Number) row[5]).intValue() : 0);
                    chunk.setTokenCount(row[6] != null ? ((Number) row[6]).intValue() : null);
                    // row[7] is created_at — we skip setting it on the transient result
                    // row[8] is the distance value from pgvector
                    if (row[8] != null) {
                        chunk.setDistance(((Number) row[8]).doubleValue());
                    }
                    return chunk;
                })
                .toList();
    }

    /**
     * Hybrid search: combines pgvector cosine similarity with PostgreSQL full-text search.
     *
     * Why two strategies:
     *   - Semantic (vector): finds conceptually related content. Good for broad questions.
     *   - Keyword (FTS):     finds exact compliance terms. Good for "AES-256", "SOC 2 Type II",
     *                        "MFA", "TLS 1.3" — terms whose embeddings may not cluster tightly.
     *
     * Score formula: (semantic_score * 0.7) + (keyword_score * 0.3)
     *   Semantic weighted higher because meaning matters more than exact term presence.
     *   Keyword weight prevents pure keyword matches (low semantic relevance) from ranking first.
     *
     * A chunk is included if EITHER condition is true:
     *   - cosine distance < :vectorThreshold  (semantically similar)
     *   - FTS query matches                   (keyword match)
     * This is a UNION approach — we don't require both, because a very relevant chunk
     * might score low on FTS if it paraphrases the term (e.g., "256-bit AES" vs "AES-256").
     *
     * Returns Object[] columns (same order as findSimilarRaw for consistent mapping):
     *   [0] id              UUID
     *   [1] organization_id UUID
     *   [2] document_id     UUID
     *   [3] section_title   String (nullable)
     *   [4] text            String
     *   [5] chunk_index     Integer
     *   [6] token_count     Integer (nullable)
     *   [7] created_at      (skipped in mapping, same as findSimilarRaw)
     *   [8] distance        Double  ← synthetic: 1.0 - combined_score, so lower = better
     *                                 Allows reuse of the same Object[] mapping logic.
     */
    @Query(value = """
    SELECT
        id,
        organization_id,
        document_id,
        section_title,
        text,
        chunk_index,
        token_count,
        created_at,
        (1.0 - combined_score) AS distance
    FROM (
        SELECT
            id,
            organization_id,
            document_id,
            section_title,
            text,
            chunk_index,
            token_count,
            created_at,
            (
                (CASE
                    WHEN embedding IS NOT NULL
                    THEN (1.0 - (embedding <=> CAST(:embedding AS vector))) * 0.7
                    ELSE 0.0
                END)
                +
                (CASE
                    WHEN to_tsvector('english', text) @@ plainto_tsquery('english', :keywords)
                    THEN ts_rank(to_tsvector('english', text),
                                 plainto_tsquery('english', :keywords)) * 0.3
                    ELSE 0.0
                END)
            ) AS combined_score
        FROM document_chunk
        WHERE organization_id = :orgId
          AND (
              (embedding <=> CAST(:embedding AS vector)) < :vectorThreshold
              OR to_tsvector('english', text) @@ plainto_tsquery('english', :keywords)
          )
    ) scored
    ORDER BY combined_score DESC
    LIMIT :limit
    """, nativeQuery = true)
    List<Object[]> findHybridRaw(
            @Param("orgId")           UUID   orgId,
            @Param("embedding")       String embedding,
            @Param("keywords")        String keywords,
            @Param("vectorThreshold") double vectorThreshold,
            @Param("limit")           int    limit
    );

    /**
     * Convenience wrapper — maps Object[] rows to DocumentChunk with distance set.
     * The 'distance' here is (1 - combined_score), so getSimilarity() still works:
     *   similarity = 1.0 - distance = combined_score
     * This lets the confidence computation in AnswerGenerationService work unchanged.
     */
    default List<DocumentChunk> findHybrid(
            UUID   orgId,
            String embedding,
            String keywords,
            double vectorThreshold,
            int    limit
    ) {
        // Sanitize keywords for plainto_tsquery — remove special chars that break FTS parsing
        String safeKeywords = keywords == null || keywords.isBlank()
                ? "security"
                : keywords.replaceAll("[^a-zA-Z0-9\\s\\-.]", " ").trim();

        List<Object[]> rows = findHybridRaw(orgId, embedding, safeKeywords, vectorThreshold, limit);
        return rows.stream()
                .map(row -> {
                    DocumentChunk chunk = new DocumentChunk();
                    chunk.setId(row[0] != null ? UUID.fromString(row[0].toString()) : null);
                    chunk.setOrganizationId(row[1] != null ? UUID.fromString(row[1].toString()) : null);
                    chunk.setDocumentId(row[2] != null ? UUID.fromString(row[2].toString()) : null);
                    chunk.setSectionTitle(row[3] != null ? row[3].toString() : null);
                    chunk.setText(row[4] != null ? row[4].toString() : "");
                    chunk.setChunkIndex(row[5] != null ? ((Number) row[5]).intValue() : 0);
                    chunk.setTokenCount(row[6] != null ? ((Number) row[6]).intValue() : null);
                    // row[7] = created_at, skipped
                    // row[8] = (1.0 - combined_score) stored as distance
                    if (row[8] != null) {
                        chunk.setDistance(((Number) row[8]).doubleValue());
                    }
                    return chunk;
                })
                .toList();
    }

    /**
     * Delete all chunks for a document (used when re-processing or deleting a doc).
     * Unchanged from Phase 3.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM DocumentChunk c WHERE c.documentId = :docId AND c.organizationId = :orgId")
    void deleteByDocumentIdAndOrganizationId(
            @Param("docId")  UUID docId,
            @Param("orgId")  UUID orgId
    );

    long countByDocumentId(UUID documentId);
}