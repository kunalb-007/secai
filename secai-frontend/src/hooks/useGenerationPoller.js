// src/hooks/useGenerationPoller.js  — NEW FILE
import { useState, useEffect, useRef, useCallback } from 'react';
import { getGenerationJob } from '../api/questionnaires';

const TERMINAL_STATUSES = ['COMPLETED', 'FAILED'];
const POLL_INTERVAL_MS  = 2000;  // 2s — generation moves fast

/**
 * Polls GET /api/questionnaires/:id/job every 2 seconds while status is RUNNING.
 * Stops automatically when status reaches COMPLETED or FAILED.
 *
 * @param {string|null} questionnaireId  - ID to poll. Pass null to disable.
 * @param {function}    onComplete       - Called with final job object when done.
 *
 * @returns {{ job, isPolling }}
 *   job: { jobId, status, totalQuestions, completedQuestions,
 *           progressPercent, statusMessage, startedAt, finishedAt }
 */
export function useGenerationPoller(questionnaireId, onComplete) {
    const [job, setJob]           = useState(null);
    const [isPolling, setIsPolling] = useState(false);
    const intervalRef             = useRef(null);
    const onCompleteRef           = useRef(onComplete);

    useEffect(() => { onCompleteRef.current = onComplete; }, [onComplete]);

    const stopPolling = useCallback(() => {
        if (intervalRef.current) {
            clearInterval(intervalRef.current);
            intervalRef.current = null;
        }
        setIsPolling(false);
    }, []);

    const poll = useCallback(async () => {
        if (!questionnaireId) return;
        try {
            const res  = await getGenerationJob(questionnaireId);
            const data = res.data;
            setJob(data);

            if (TERMINAL_STATUSES.includes(data.status)) {
                stopPolling();
                onCompleteRef.current?.(data);
            }
        } catch (err) {
            // Network error — stop polling rather than looping errors
            console.error('Generation poll error:', err);
            stopPolling();
        }
    }, [questionnaireId, stopPolling]);

    useEffect(() => {
        if (!questionnaireId) return;

        setIsPolling(true);
        poll(); // immediate first call

        intervalRef.current = setInterval(poll, POLL_INTERVAL_MS);

        return () => stopPolling();
    }, [questionnaireId, poll, stopPolling]);

    return { job, isPolling };
}