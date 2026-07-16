package com.secai.dto.document;

import java.util.UUID;

/**
 * Returned by GET /api/chunks/{id}
 * Powers the "View Source" button in the review UI.
 * Shows the reviewer the exact paragraph the AI read before generating an answer.
 */
public record ChunkSourceResponse(
        UUID   chunkId,
        UUID   documentId,
        String documentFilename,
        String sectionTitle,
        String text              // the actual paragraph text
) {}