package com.secai.controller;

import com.secai.dto.library.ApprovedAnswerMatch;
import com.secai.service.AnswerLibraryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/library")
public class AnswerLibraryController {

    private final AnswerLibraryService libraryService;

    public AnswerLibraryController(AnswerLibraryService libraryService) {
        this.libraryService = libraryService;
    }

    /**
     * GET /api/library/match?questionId={id}
     *
     * Searches the org's answer library for a question similar to the given one.
     * Returns the best match if similarity ≥ 82%, otherwise 204 No Content.
     *
     * The frontend calls this per-question when rendering the review table —
     * results are cached in component state to avoid redundant calls.
     */
    @GetMapping("/match")
    public ResponseEntity<ApprovedAnswerMatch> findMatch(
            @RequestParam UUID questionId
    ) {
        return libraryService.findMatch(questionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * POST /api/library/reuse
     * Body: { "questionId": "...", "libraryEntryId": "..." }
     *
     * Applies the library answer to the question (sets manual_answer + APPROVED).
     * Returns 204 No Content on success — the frontend refreshes the question row.
     */
    @PostMapping("/reuse")
    public ResponseEntity<Void> reuseAnswer(@RequestBody ReuseRequest req) {
        libraryService.reuseAnswer(req.questionId(), req.libraryEntryId());
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/library/stats
     * Returns: { "librarySize": 142 }
     *
     * Shown on the dashboard as a proof-of-value metric.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> stats() {
        return ResponseEntity.ok(Map.of("librarySize", libraryService.getLibrarySize()));
    }

    public record ReuseRequest(UUID questionId, UUID libraryEntryId) {}
}