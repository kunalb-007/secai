// src/api/library.js
import client from './client';

/**
 * GET /api/library/match?questionId={id}
 * Returns ApprovedAnswerMatch or null (204 = no match).
 */
export const findLibraryMatch = async (questionId) => {
    try {
        const res = await client.get('/library/match', { params: { questionId } });
        return res.status === 204 ? null : res.data;
    } catch (err) {
        if (err.response?.status === 204 || err.response?.status === 404) return null;
        throw err;
    }
};

/**
 * POST /api/library/reuse
 * Applies a library answer to a question.
 */
export const reuseLibraryAnswer = (questionId, libraryEntryId) =>
    client.post('/library/reuse', { questionId, libraryEntryId });

/**
 * GET /api/library/stats
 * Returns { librarySize: number }
 */
export const getLibraryStats = () => client.get('/library/stats');