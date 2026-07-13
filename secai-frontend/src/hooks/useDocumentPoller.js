// src/hooks/useDocumentPoller.js
import { useState, useEffect, useRef, useCallback } from 'react';
import { getDocumentStatus } from '../api/documents';

const TERMINAL_STATUSES = ['READY', 'FAILED'];
const POLL_INTERVAL_MS  = 4000;

/**
 * Polls GET /api/documents/:id/status every 4 seconds.
 * Stops automatically when status reaches READY or FAILED.
 *
 * @param {string|null} documentId  - ID to poll. Pass null to disable.
 * @param {function}    onComplete  - Called with final status object when polling ends.
 *
 * @returns {{ status, chunkCount, errorMessage, isPolling }}
 */
export function useDocumentPoller(documentId, onComplete) {
    const [statusData, setStatusData] = useState(null);
    const [isPolling, setIsPolling]   = useState(false);
    const intervalRef  = useRef(null);
    const onCompleteRef = useRef(onComplete);

    // Keep onComplete ref fresh without triggering effect re-run
    useEffect(() => { onCompleteRef.current = onComplete; }, [onComplete]);

    const stopPolling = useCallback(() => {
        if (intervalRef.current) {
            clearInterval(intervalRef.current);
            intervalRef.current = null;
        }
        setIsPolling(false);
    }, []);

    const poll = useCallback(async () => {
        if (!documentId) return;
        try {
            const res = await getDocumentStatus(documentId);
            const data = res.data;
            setStatusData(data);

            if (TERMINAL_STATUSES.includes(data.status)) {
                stopPolling();
                onCompleteRef.current?.(data);
            }
        } catch (err) {
            // Network error during poll — don't crash, just stop
            console.error('Polling error:', err);
            stopPolling();
        }
    }, [documentId, stopPolling]);

    useEffect(() => {
        if (!documentId) return;

        setIsPolling(true);
        poll(); // immediate first call

        intervalRef.current = setInterval(poll, POLL_INTERVAL_MS);

        return () => stopPolling();
    }, [documentId, poll, stopPolling]);

    return {
        status:       statusData?.status       ?? null,
        chunkCount:   statusData?.chunkCount   ?? null,
        errorMessage: statusData?.errorMessage ?? null,
        isPolling,
    };
}