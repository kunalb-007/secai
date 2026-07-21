import client from './client';

/**
 * GET /api/chunks/:id
 * Fetches the stored chunk text and metadata for the Evidence Details modal.
 * Returns: { chunkId, documentId, documentFilename, sectionTitle, text }
 */
export const getChunk = (chunkId) => client.get(`/chunks/${chunkId}`);