package com.secai.domain.questionnaire;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AiGenerationJobRepository extends JpaRepository<AiGenerationJob, UUID> {

    Optional<AiGenerationJob> findByQuestionnaireId(UUID questionnaireId);
}