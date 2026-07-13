package com.secai.domain.questionnaire;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuestionnaireRepository extends JpaRepository<Questionnaire, UUID> {

    List<Questionnaire> findByOrganizationIdOrderByUploadedAtDesc(UUID organizationId);

    Optional<Questionnaire> findByIdAndOrganizationId(UUID id, UUID organizationId);
}