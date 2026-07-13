package com.secai.domain.document;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 3 entity — Phase 5 adds the getSimilarity() transient helper
 * so AnswerGenerationService can read cosine similarity from native query results.
 *
 * IMPORTANT: Replace the existing DocumentChunk.java with this version.
 * The only additions vs Phase 3 are:
 *   - @Transient private Double distance field
 *   - getSimilarity() method
 *   - the @SqlResultSetMapping (if you prefer JPQL; otherwise use native query as before)
 */
@Entity
@Table(name = "document_chunk")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /**
     * CRITICAL: every query filters on this. Never omit it.
     */
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "section_title")
    private String sectionTitle;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    /**
     * pgvector embedding stored as float array.
     */
    @Column(columnDefinition = "VECTOR(1536)")
    private float[] embedding;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Phase 5 ADDITION — transient field populated by native vector-search queries.
     *
     * The pgvector <=> operator returns COSINE DISTANCE (0 = identical, 2 = opposite).
     * We store it here so AnswerGenerationService can convert to similarity.
     *
     * This field is NOT a DB column — it is set programmatically after query execution
     * when using the custom repository method that selects the distance alias.
     */
    @Transient
    private Double distance;

    /**
     * Returns cosine similarity (0.0 – 1.0) from the stored distance value.
     *
     * cosine_similarity = 1.0 - cosine_distance
     *
     * Returns 0.0 if no distance has been set (chunk was fetched without
     * a similarity search, e.g. by document ID).
     */
    public double getSimilarity() {
        if (distance == null) return 0.0;
        return Math.max(0.0, 1.0 - distance);
    }

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}