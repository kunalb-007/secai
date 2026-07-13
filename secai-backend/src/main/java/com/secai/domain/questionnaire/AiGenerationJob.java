package com.secai.domain.questionnaire;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_generation_job")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiGenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "questionnaire_id", nullable = false)
    private UUID questionnaireId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiJobStatus status;

    @Column(name = "total_questions")
    private int totalQuestions;

    @Column(name = "completed_questions")
    private int completedQuestions;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
        if (status == null) status = AiJobStatus.PENDING;
    }

    public enum AiJobStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}