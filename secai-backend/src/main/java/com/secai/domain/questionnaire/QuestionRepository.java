package com.secai.domain.questionnaire;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {

    // Full list — used for export and AI generation
    List<Question> findByQuestionnaireIdAndOrganizationIdOrderBySortOrder(
            UUID questionnaireId, UUID organizationId
    );

    // Paginated — used for review UI (400+ questions need paging)
    Page<Question> findByQuestionnaireIdAndOrganizationIdOrderBySortOrder(
            UUID questionnaireId, UUID organizationId, Pageable pageable
    );

    // Filtered by status — used for "show only pending" etc.
    Page<Question> findByQuestionnaireIdAndOrganizationIdAndStatusOrderBySortOrder(
            UUID questionnaireId, UUID organizationId, QuestionStatus status, Pageable pageable
    );

    // Count by status — for the stats strip on the review page
    @Query("""
        SELECT q.status, COUNT(q)
        FROM Question q
        WHERE q.questionnaireId = :qid AND q.organizationId = :orgId
        GROUP BY q.status
        """)
    List<Object[]> countByStatus(
            @Param("qid")   UUID questionnaireId,
            @Param("orgId") UUID organizationId
    );

    long countByQuestionnaireIdAndOrganizationId(UUID questionnaireId, UUID organizationId);

    // Delete all questions when deleting a questionnaire
    void deleteByQuestionnaireIdAndOrganizationId(UUID questionnaireId, UUID organizationId);
}