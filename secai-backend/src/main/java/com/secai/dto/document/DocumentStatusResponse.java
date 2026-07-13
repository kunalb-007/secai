package com.secai.dto.document;

import com.secai.domain.document.DocumentStatus;
import java.util.UUID;

public record DocumentStatusResponse(
        UUID           id,
        DocumentStatus status,
        String         errorMessage,
        Long           chunkCount    // populated once READY, null otherwise
) {}