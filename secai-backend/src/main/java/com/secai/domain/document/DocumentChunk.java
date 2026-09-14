package com.secai.domain.document;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

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