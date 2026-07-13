package com.secai.domain.document;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "document")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;   // NOT a FK object — intentional for tenant filtering

    @Column(nullable = false)
    private String filename;

    @Column(name = "content_type")
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Column(name = "storage_path")
    private String storagePath;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @PrePersist
    void prePersist() {
        uploadedAt = OffsetDateTime.now();
        if (status == null) status = DocumentStatus.PENDING;
    }
}