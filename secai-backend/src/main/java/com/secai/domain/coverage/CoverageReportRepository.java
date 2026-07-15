package com.secai.domain.coverage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CoverageReportRepository extends JpaRepository<CoverageReport, UUID> {

    Optional<CoverageReport> findByQuestionnaireId(UUID questionnaireId);

    Optional<CoverageReport> findByQuestionnaireIdAndOrganizationId(
            UUID questionnaireId, UUID organizationId
    );
}