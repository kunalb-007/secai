// src/api/documents.js
import client from './client';

/**
 * Upload a security document (PDF, DOCX, TXT).
 * Returns: { documentId, filename, status, message }
 */
export const uploadDocument = (file, onProgress) => {
    const formData = new FormData();
    formData.append('file', file);
    return client.post('/documents', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (e) => {
            if (onProgress) onProgress(Math.round((e.loaded * 100) / e.total));
        },
    });
};

/**
 * List all documents for the authenticated org.
 * Returns: [{ id, filename, status, errorMessage, uploadedAt, processedAt }]
 */
export const listDocuments = () => client.get('/documents');

/**
 * Get a single document by ID.
 * Returns: { id, filename, status, errorMessage, uploadedAt, processedAt }
 */
export const getDocument = (id) => client.get(`/documents/${id}`);

/**
 * Lightweight status polling — returns only status + chunkCount.
 * Used for polling during PENDING/PROCESSING state.
 * Returns: { id, status, errorMessage, chunkCount }
 */
export const getDocumentStatus = (id) => client.get(`/documents/${id}/status`);

/**
 * Delete a document and its chunks.
 */
export const deleteDocument = (id) => client.delete(`/documents/${id}`);