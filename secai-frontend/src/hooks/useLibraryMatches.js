// src/hooks/useLibraryMatches.js
import { useState, useEffect, useRef, useCallback } from 'react';
import { findLibraryMatch } from '../api/library';

/**
 * For a list of questions, fetches library matches in the background —
 * staggered to avoid hammering the embedding API with 50 simultaneous calls.
 *
 * Returns a Map<questionId, ApprovedAnswerMatch | null>.
 *
 * Only queries questions that are GENERATED (have an AI answer but haven't
 * been reviewed yet) — there's no point surfacing a library suggestion for
 * a question the reviewer has already handled.
 *
 * Stagger delay: 80ms between requests → 50 questions takes ~4 seconds,
 * fast enough to feel instant but well within rate limits.
 */
export function useLibraryMatches(questions) {
    const [matches, setMatches] = useState({}); // { [questionId]: match | null }
    const inFlight              = useRef(new Set());

    const fetchMatch = useCallback(async (questionId) => {
        if (inFlight.current.has(questionId)) return;
        inFlight.current.add(questionId);

        try {
            const match = await findLibraryMatch(questionId);
            setMatches((prev) => ({ ...prev, [questionId]: match }));
        } catch {
            // Silently ignore — library matches are best-effort
            setMatches((prev) => ({ ...prev, [questionId]: null }));
        } finally {
            inFlight.current.delete(questionId);
        }
    }, []);

    useEffect(() => {
        // Only fetch for GENERATED questions not yet in the matches map
        const toFetch = questions.filter(
            (q) => q.status === 'GENERATED' && !(q.id in matches)
        );

        if (!toFetch.length) return;

        // Stagger requests
        toFetch.forEach((q, i) => {
            setTimeout(() => fetchMatch(q.id), i * 80);
        });
    }, [questions]); // eslint-disable-line react-hooks/exhaustive-deps

    // Clear stale entries when question list changes substantially
    useEffect(() => {
        const currentIds = new Set(questions.map((q) => q.id));
        setMatches((prev) => {
            const next = {};
            Object.keys(prev).forEach((id) => {
                if (currentIds.has(id)) next[id] = prev[id];
            });
            return next;
        });
    }, [questions.map((q) => q.id).join(',')]); // eslint-disable-line react-hooks/exhaustive-deps

    return matches;
}