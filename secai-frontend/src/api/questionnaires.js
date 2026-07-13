// src/api/questionnaires.js  — REPLACE ENTIRE FILE (Phase 5 update)
import client from './client';

/** Upload and parse a questionnaire file (XLSX, CSV, DOCX). */
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

/** List all questionnaires for the authenticated org. */
export const listQuestionnaires = () => client.get('/questionnaires');

/** Get questionnaire detail with status counts and AI job summary. */
export const getQuestionnaire = (id) => client.get(`/questionnaires/${id}`);

/**
 * Get paginated questions for a questionnaire.
 * Returns a Spring Page object: { content, totalElements, totalPages, ... }
 */
export const getQuestions = (id, { status = '', page = 0, size = 50 } = {}) =>
    client.get(`/questionnaires/${id}/questions`, {
        params: { status: status || undefined, page, size },
    });

/** Delete a questionnaire and all its questions. */
export const deleteQuestionnaire = (id) => client.delete(`/questionnaires/${id}`);

// ── Phase 5 additions ─────────────────────────────────────────────────────────

/**
 * POST /api/questionnaires/{id}/generate
 * Triggers AI answer generation. Returns immediately with job status.
 * The job runs asynchronously — poll getGenerationJob() for progress.
 */
export const startGeneration = (id) => client.post(`/questionnaires/${id}/generate`);

/**
 * GET /api/questionnaires/{id}/job
 * Lightweight polling endpoint.
 * Returns: { jobId, questionnaireId, status, totalQuestions,
 *            completedQuestions, progressPercent, statusMessage,
 *            startedAt, finishedAt }
 */
export const getGenerationJob = (id) => client.get(`/questionnaires/${id}/job`);

/**
 * PUT /api/questions/{id}
 * Edit an AI-generated answer. Sets status → EDITED.
 * Body: { manualAnswer: "..." }
 */
export const editQuestion = (questionId, manualAnswer) =>
    client.put(`/questions/${questionId}`, { manualAnswer });

/**
 * POST /api/questions/{id}/approve
 * Approve the current answer. Sets status → APPROVED.
 */
export const approveQuestion = (questionId) =>
    client.post(`/questions/${questionId}/approve`);

/**
 * POST /api/questions/{id}/reject
 * Reject the AI answer. Sets status → REJECTED.
 */
export const rejectQuestion = (questionId) =>
    client.post(`/questions/${questionId}/reject`);