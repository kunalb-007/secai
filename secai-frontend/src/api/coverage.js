// src/api/coverage.js
import client from './client';

/**
 * GET /api/questionnaires/{id}/coverage
 * Returns CoverageReportResponse.
 * Status: PENDING | RUNNING | COMPLETE | FAILED
 */
export const getCoverage = (questionnaireId) =>
    client.get(`/questionnaires/${questionnaireId}/coverage`);

/**
 * POST /api/questionnaires/{id}/coverage/refresh
 * Re-runs the coverage analysis (call after uploading new documents).
 * Returns 202 Accepted — poll getCoverage() for results.
 */
export const refreshCoverage = (questionnaireId) =>
    client.post(`/questionnaires/${questionnaireId}/coverage/refresh`);