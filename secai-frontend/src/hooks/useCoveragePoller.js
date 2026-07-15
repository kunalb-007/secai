// src/hooks/useCoveragePoller.js
import { useState, useEffect, useRef, useCallback } from 'react';
import { getCoverage } from '../api/coverage';

const TERMINAL = ['COMPLETE', 'FAILED'];
const INTERVAL = 3000; // 3s — analysis takes 20–60s, no need to hammer

/**
 * Polls /api/questionnaires/{id}/coverage every 3 seconds.
 * Stops when status reaches COMPLETE or FAILED.
 *
 * @param {string} questionnaireId
 * @param {function} onComplete  called with final CoverageReportResponse
 * @returns {{ report, isPolling }}
 */
export function useCoveragePoller(questionnaireId, onComplete) {
    const [report, setReport]       = useState(null);
    const [isPolling, setIsPolling] = useState(false);
    const timerRef                  = useRef(null);
    const onCompleteRef             = useRef(onComplete);

    useEffect(() => { onCompleteRef.current = onComplete; }, [onComplete]);

    const stop = useCallback(() => {
        clearInterval(timerRef.current);
        timerRef.current = null;
        setIsPolling(false);
    }, []);

    const poll = useCallback(async () => {
        if (!questionnaireId) return;
        try {
            const res = await getCoverage(questionnaireId);
            setReport(res.data);
            if (TERMINAL.includes(res.data.status)) {
                stop();
                onCompleteRef.current?.(res.data);
            }
        } catch {
            stop(); // stop on network error
        }
    }, [questionnaireId, stop]);

    useEffect(() => {
        if (!questionnaireId) return;
        setIsPolling(true);
        poll();
        timerRef.current = setInterval(poll, INTERVAL);
        return () => stop();
    }, [questionnaireId, poll, stop]);

    return { report, isPolling };
}