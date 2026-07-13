// src/api/questionnaires.js
import client from './client';

/**
 * Upload and parse a questionnaire file (XLSX, CSV, DOCX).
 * Returns: { questionnaireId, filename, status, totalQuestions,
 *            lowConfidenceFlag, warningMessage, preview }
 */
export const uploadQuestionnaire = (file, onProgress) => {
    const formData = new FormData();
    formData.append('file', file);
    return client.post('/questionnaires', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (e) => {
            if (onProgress) onProgress(Math.round((e.loaded * 100) / e.total));
        },
    });
};

/**
 * List all questionnaires for the authenticated org.
 * Returns: [{ id, filename, originalFormat, status, totalQuestions,
 *             lowConfidenceFlag, uploadedAt }]
 */
export const listQuestionnaires = () => client.get('/questionnaires');

/**
 * Get questionnaire detail with status counts and AI job info.
 * Returns: { id, filename, status, totalQuestions, statusCounts, aiJob, ... }
 */
export const getQuestionnaire = (id) => client.get(`/questionnaires/${id}`);

/**
 * Get paginated questions for a questionnaire.
 * Returns a Spring Page object: { content, totalElements, totalPages, ... }
 */
export const getQuestions = (id, { status = '', page = 0, size = 50 } = {}) =>
    client.get(`/questionnaires/${id}/questions`, {
        params: { status: status || undefined, page, size },
    });

/**
 * Delete a questionnaire and all its questions.
 */
export const deleteQuestionnaire = (id) => client.delete(`/questionnaires/${id}`);