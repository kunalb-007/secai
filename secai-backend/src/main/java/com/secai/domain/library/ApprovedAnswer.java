package com.secai.domain.library;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "approved_answer")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApprovedAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /** Nullable — the source question may be deleted later */
    @Column(name = "source_question_id")
    private UUID sourceQuestionId;

    @Column(name = "source_question_text", nullable = false, columnDefinition = "TEXT")
    private String sourceQuestionText;

    @Column(name = "answer_text", nullable = false, columnDefinition = "TEXT")
    private String answerText;

    @Column(name = "evidence")
    private String evidence;

    @Column(name = "source_chunk_id")
    private UUID sourceChunkId;

    // NEW — the document ID that provided the supporting evidence.
// Stored when an answer is approved, surfaced via ApprovedAnswerMatch
// so the frontend can render a clickable "Open source document" chip
// even when the answer is reused from organizational memory.
    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "approved_by_email")
    private String approvedByEmail;

    @Column(name = "approved_at", nullable = false)
    private OffsetDateTime approvedAt;

    /** 1536-dim embedding of sourceQuestionText, same model as document chunks */
    @Column(name = "question_embedding", columnDefinition = "VECTOR(1536)")
    private float[] questionEmbedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
        if (approvedAt == null) approvedAt = OffsetDateTime.now();
    }

    /** Transient similarity score — populated by repository after vector search */
    @Transient
    private Double distance;

    /** Cosine similarity = 1.0 - cosine_distance */
    public double getSimilarity() {
        return distance == null ? 0.0 : Math.max(0.0, 1.0 - distance);
    }
}