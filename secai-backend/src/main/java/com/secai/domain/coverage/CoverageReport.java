package com.secai.domain.coverage;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "coverage_report")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CoverageReport {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "questionnaire_id", nullable = false)
    private UUID questionnaireId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "overall_coverage", nullable = false, precision = 5, scale = 4)
    private BigDecimal overallCoverage;

    @Column(name = "total_questions", nullable = false)
    private int totalQuestions;

    @Column(name = "answerable_questions", nullable = false)
    private int answerableQuestions;

    /**
     * Per-category breakdown as a JSON array stored in Postgres JSONB.
     * Mapped as List<CategoryCoverage> via Hibernate's JSON type.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_breakdown", columnDefinition = "jsonb")
    private List<CategoryCoverage> categoryBreakdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_doc_suggestions", columnDefinition = "jsonb")
    private List<String> missingDocSuggestions;

    @Column(name = "estimated_coverage_after", precision = 5, scale = 4)
    private BigDecimal estimatedCoverageAfter;

    @Column(name = "status", nullable = false)
    private String status;  // PENDING | RUNNING | COMPLETE | FAILED

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = OffsetDateTime.now();
        if (status == null) status = "PENDING";
    }

    // ── Nested value object ───────────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CategoryCoverage {
        private String  category;
        private double  coverage;            // 0.0–1.0
        private int     totalQuestions;
        private int     answerableQuestions;
        private String  missingDocSuggestion; // nullable
        private String  coverageTier;        // HIGH | MEDIUM | LOW | CRITICAL
    }
}