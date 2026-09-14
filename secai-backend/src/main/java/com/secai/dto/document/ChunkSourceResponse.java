package com.secai.dto.document;

import java.util.UUID;

public record ChunkSourceResponse(
        UUID   chunkId,
        UUID   documentId,
        String documentFilename,
        String sectionTitle,
        String text              // the actual paragraph text
) {}