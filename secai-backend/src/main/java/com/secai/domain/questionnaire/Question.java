package com.secai.domain.questionnaire;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "question")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "questionnaire_id", nullable = false)
    private UUID questionnaireId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "question_number")
    private String questionNumber;          // "1.1", "Q42", "3"

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "category")
    private String category;                // sheet name or DOCX section header

    @Column(name = "ai_answer", columnDefinition = "TEXT")
    private String aiAnswer;

    @Column(name = "evidence")
    private String evidence;                // "Security Policy, Section: Encryption"

    @Column(name = "retrieval_score")
    private Double retrievalScore;          // 0.0000 – 1.0000

    /**
     * The document chunk that provided the strongest evidence for this answer.
     * Nullable — set during AI generation, null for unanswerable questions.
     * Used by the review UI "View Source" feature.
     */
    @Column(name = "source_chunk_id")
    private UUID sourceChunkId;

    /**
     * The document the source chunk belongs to.
     * Denormalized here to avoid a join when building the "View Source" response.
     */
    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionStatus status;

    @Column(name = "manual_answer", columnDefinition = "TEXT")
    private String manualAnswer;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;                  // preserves original row order from file

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
        if (status == null) status = QuestionStatus.PENDING;
    }
}