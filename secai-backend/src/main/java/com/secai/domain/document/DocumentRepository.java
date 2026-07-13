package com.secai.domain.document;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    // ALWAYS filter by organizationId — never query without it
    List<Document> findByOrganizationIdOrderByUploadedAtDesc(UUID organizationId);

    Optional<Document> findByIdAndOrganizationId(UUID id, UUID organizationId);
}