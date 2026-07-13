package com.secai.dto.document;

import com.secai.domain.document.DocumentStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentListResponse(
        UUID id,
        String filename,
        DocumentStatus status,
        String         errorMessage,
        OffsetDateTime uploadedAt,
        OffsetDateTime processedAt
) {}