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
     * Mapped as float[] — pgvector JDBC driver handles the VECTOR type conversion.
     */
    @Column(columnDefinition = "VECTOR(1536)")
    private float[] embedding;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
    }
}