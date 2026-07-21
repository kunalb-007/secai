package com.secai.domain.document;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DocumentChunkRepository — Phase 5 + Retrieval Fix.
 *
 * RETRIEVAL BUGS FIXED:
 *
 * BUG 1 — Hybrid search was effectively an INTERSECT, not UNION.
 *   The original WHERE clause was:
 *     WHERE org = :orgId
 *       AND (vector_distance < threshold OR fts_match)
 *   This looks like UNION but the vector_distance < threshold predicate silently
 *   EXCLUDED chunks that only matched via FTS (because pgvector still computed the
 *   distance and rows without an embedding or with distance ≥ threshold were dropped
 *   by the planner before the OR was evaluated on older pgvector builds).
 *   Fix: use a true UNION ALL of two separate subqueries, then deduplicate by id.
 *
 * BUG 2 — Vector threshold 0.35 is too tight for a small corpus.
 *   With only 6 chunks covering 23 topics, each chunk contains multiple topics.
 *   The semantic distance between "MFA" and a chunk discussing auth + passwords
 *   can be 0.38–0.45, just over the cutoff.
 *   Fix: raise VECTOR_THRESHOLD to 0.50 in AnswerGenerationService and this query.
 *   The hybrid reranking then keeps only the genuinely relevant results.
 *
 * BUG 3 — FTS keyword scoring was not normalized.
 *   ts_rank returns values in [0, 1] for short texts but can exceed 1.0 for
 *   long texts with many matches. Capping at 1.0 prevents FTS from dominating.
 *   Fix: use LEAST(ts_rank(...), 1.0) in the scoring formula.
 *
 * HYBRID SCORING FORMULA (unchanged but now correctly applied):
 *   combined_score = (semantic_similarity * 0.7) + (LEAST(fts_rank, 1.0) * 0.3)
 *   distance       = 1.0 - combined_score   (lower = better, reuses mapping logic)
 *
 * Column order in Object[] (same for findSimilarRaw and findHybridRaw):
 *   [0] id UUID  [1] org UUID  [2] doc UUID  [3] section_title  [4] text
 *   [5] chunk_index  [6] token_count  [7] created_at  [8] distance
 */
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    // ── Pure vector search (coverage analysis, document detail) ──────────────

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

    default List<DocumentChunk> findSimilar(
            UUID orgId, String embedding, double threshold, int limit
    ) {
        return mapRows(findSimilarRaw(orgId, embedding, threshold, limit));
    }

    // ── Hybrid search (answer generation) ────────────────────────────────────

    /**
     * TRUE UNION hybrid search.
     *
     * Approach: two separate subqueries (vector branch + FTS branch),
     * UNION ALL to merge, then aggregate by id to keep the best score per chunk,
     * then rank by combined score descending.
     *
     * Vector branch:
     *   - Selects chunks where cosine distance < vectorThreshold
     *   - semantic_score = 1.0 - cosine_distance
     *   - fts_score = ts_rank if FTS also matches, else 0.0
     *
     * FTS branch:
     *   - Selects chunks where plainto_tsquery matches the text
     *   - fts_score = LEAST(ts_rank, 1.0)   (normalised)
     *   - semantic_score = 1.0 - cosine_distance if embedding exists, else 0.0
     *   - NOTE: chunks that ONLY match via FTS but have distance ≥ vectorThreshold
     *     ARE included — this is the fix for the INTERSECT bug.
     *
     * Deduplication: GROUP BY id, take MAX of each score component.
     *   A chunk appearing in both branches keeps the best score from each.
     *
     * Final scoring:
     *   combined_score = (semantic_score * 0.7) + (fts_score * 0.3)
     *   distance = 1.0 - combined_score
     *   Stored in column [8] so getSimilarity() = 1 - distance = combined_score.
     *
     * Result capped at LIMIT after sorting — same top-K guarantee as before.
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
                (MAX(sem) * 0.7 + MAX(fts) * 0.3) AS combined_score
            FROM (
                -- ── Vector branch: semantically similar chunks ──────────────
                SELECT
                    id,
                    organization_id,
                    document_id,
                    section_title,
                    text,
                    chunk_index,
                    token_count,
                    created_at,
                    (1.0 - (embedding <=> CAST(:embedding AS vector))) AS sem,
                    CASE
                        WHEN to_tsvector('english', text) @@ plainto_tsquery('english', :keywords)
                        THEN LEAST(ts_rank(to_tsvector('english', text),
                                          plainto_tsquery('english', :keywords)), 1.0)
                        ELSE 0.0
                    END AS fts
                FROM document_chunk
                WHERE organization_id = :orgId
                  AND embedding IS NOT NULL
                  AND (embedding <=> CAST(:embedding AS vector)) < :vectorThreshold

                UNION ALL

                -- ── FTS branch: keyword-matching chunks ────────────────────
                -- Included even when cosine distance ≥ vectorThreshold.
                -- This fixes the "INTERSECT" bug where FTS-only matches were dropped.
                SELECT
                    id,
                    organization_id,
                    document_id,
                    section_title,
                    text,
                    chunk_index,
                    token_count,
                    created_at,
                    CASE
                        WHEN embedding IS NOT NULL
                        THEN GREATEST(0.0, 1.0 - (embedding <=> CAST(:embedding AS vector)))
                        ELSE 0.0
                    END AS sem,
                    LEAST(ts_rank(to_tsvector('english', text),
                                  plainto_tsquery('english', :keywords)), 1.0) AS fts
                FROM document_chunk
                WHERE organization_id = :orgId
                  AND to_tsvector('english', text) @@ plainto_tsquery('english', :keywords)
            ) branches
            GROUP BY id, organization_id, document_id, section_title,
                     text, chunk_index, token_count, created_at
        ) scored
        WHERE combined_score > 0.0
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

    default List<DocumentChunk> findHybrid(
            UUID orgId, String embedding, String keywords,
            double vectorThreshold, int limit
    ) {
        // Sanitize for plainto_tsquery: remove special chars that break FTS parsing.
        // plainto_tsquery is safe against SQL injection but throws on some punctuation.
        // AFTER
// Keep dots and digits so version numbers and standard names survive:
// "NIST 800-53", "PCI DSS 3.2", "TLS 1.3", "AES-256/GCM".
// plainto_tsquery handles tokenization; we only need to strip chars
// that cause parse errors: single-quotes, backslashes, colons, parens.
        String safeKeywords = (keywords == null || keywords.isBlank())
                ? "security"
                : keywords.replaceAll("[^a-zA-Z0-9\\s\\-./_]", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        if (safeKeywords.isBlank()) safeKeywords = "security";

        return mapRows(findHybridRaw(orgId, embedding, safeKeywords, vectorThreshold, limit));
    }

    // ── Shared Object[] → DocumentChunk mapper ────────────────────────────────

    private static List<DocumentChunk> mapRows(List<Object[]> rows) {
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
                    // row[7] = created_at — skipped in transient result
                    // row[8] = distance (pure vector) or (1 - combined_score) for hybrid
                    if (row[8] != null) {
                        chunk.setDistance(((Number) row[8]).doubleValue());
                    }
                    return chunk;
                })
                .toList();
    }

    // ── Maintenance queries ───────────────────────────────────────────────────

    @Modifying
    @Transactional
    @Query("DELETE FROM DocumentChunk c WHERE c.documentId = :docId AND c.organizationId = :orgId")
    void deleteByDocumentIdAndOrganizationId(
            @Param("docId") UUID docId,
            @Param("orgId") UUID orgId
    );

    long countByDocumentId(UUID documentId);

    Optional<DocumentChunk> findByIdAndOrganizationId(UUID id, UUID organizationId);

}