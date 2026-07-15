package com.secai.domain.library;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovedAnswerRepository extends JpaRepository<ApprovedAnswer, UUID> {

    /**
     * Core library search — finds the top K approved answers whose question
     * embedding is within cosine distance threshold of the query embedding.
     *
     * Returns Object[] rows (same mapping pattern as DocumentChunkRepository):
     *   [0] id                   UUID
     *   [1] organization_id      UUID
     *   [2] source_question_id   UUID (nullable)
     *   [3] source_question_text String
     *   [4] answer_text          String
     *   [5] evidence             String (nullable)
     *   [6] approved_by_email    String (nullable)
     *   [7] approved_at          OffsetDateTime
     *   [8] distance             Double  ← pgvector cosine distance
     */
    @Query(value = """
        SELECT
            id,
            organization_id,
            source_question_id,
            source_question_text,
            answer_text,
            evidence,
            approved_by_email,
            approved_at,
            (question_embedding <=> CAST(:embedding AS vector)) AS distance
        FROM approved_answer
        WHERE organization_id = :orgId
          AND question_embedding <=> CAST(:embedding AS vector) < :threshold
        ORDER BY question_embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findSimilarRaw(
            @Param("orgId")     UUID   orgId,
            @Param("embedding") String embedding,
            @Param("threshold") double threshold,
            @Param("limit")     int    limit
    );

    /**
     * Check if a library entry already exists for a given source question.
     * Used to avoid duplicate entries when a question is re-approved.
     */
    Optional<ApprovedAnswer> findBySourceQuestionId(UUID sourceQuestionId);

    /** Count library entries for an org — shown on the dashboard. */
    long countByOrganizationId(UUID organizationId);
}