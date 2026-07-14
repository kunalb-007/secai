// src/api/questionnaires.js
import client from './client';

export const uploadQuestionnaire = (file, onProgress) => {
    const fd = new FormData();
    fd.append('file', file);
    return client.post('/questionnaires', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (e) => {
            if (onProgress) onProgress(Math.round((e.loaded * 100) / e.total));
        },
    });
};

export const listQuestionnaires = ()     => client.get('/questionnaires');
export const getQuestionnaire   = (id)  => client.get(`/questionnaires/${id}`);
export const deleteQuestionnaire= (id)  => client.delete(`/questionnaires/${id}`);

export const getQuestions = (id, { status = '', filter = '', page = 0, size = 50 } = {}) =>
    client.get(`/questionnaires/${id}/questions`, {
        params: {
            filter: filter  || undefined,
            status: status  || undefined,
            page,
            size,
        },
    });

// Phase 5 – generation
export const startGeneration  = (id) => client.post(`/questionnaires/${id}/generate`);
export const getGenerationJob = (id) => client.get(`/questionnaires/${id}/job`);

// Phase 6 – review
export const editQuestion    = (qid, manualAnswer) =>
    client.put(`/questions/${qid}`, { manualAnswer });
export const approveQuestion = (qid) => client.post(`/questions/${qid}/approve`);
export const rejectQuestion  = (qid) => client.post(`/questions/${qid}/reject`);

export const bulkApprove = (questionnaireId, questionIds) =>
    client.post(`/questionnaires/${questionnaireId}/questions/bulk-approve`, { questionIds });

// Phase 7 – export
// Returns a blob URL the browser can download.
// Usage: const url = await getExportUrl(id); window.location.href = url;
export const exportQuestionnaire = (id) =>
    client.get(`/questionnaires/${id}/export`, { responseType: 'blob' });