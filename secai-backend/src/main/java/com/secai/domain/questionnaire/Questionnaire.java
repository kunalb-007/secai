package com.secai.domain.questionnaire;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "questionnaire")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Questionnaire {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "original_format", nullable = false)
    private String originalFormat;          // "XLSX", "CSV", "DOCX"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionnaireStatus status;

    @Column(name = "total_questions")
    private int totalQuestions;

    @Column(name = "parse_confidence")
    private Double parseConfidence;         // 0.0 – 1.0

    @Column(name = "low_confidence_flag")
    private boolean lowConfidenceFlag;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;

    @PrePersist
    void prePersist() {
        uploadedAt = OffsetDateTime.now();
        if (status == null) status = QuestionnaireStatus.UPLOADED;
    }
}