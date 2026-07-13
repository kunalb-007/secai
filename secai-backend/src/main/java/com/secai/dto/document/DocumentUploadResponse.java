package com.secai.dto.document;

import com.secai.domain.document.DocumentStatus;
import java.util.UUID;

public record DocumentUploadResponse(
        UUID documentId,
        String filename,
        DocumentStatus status,
        String message
) {}