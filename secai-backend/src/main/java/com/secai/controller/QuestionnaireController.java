package com.secai.controller;

import com.secai.config.TenantContext;
import com.secai.domain.questionnaire.AiGenerationJob;
import com.secai.dto.questionnaire.*;
import com.secai.exception.NotFoundException;
import com.secai.service.AnswerGenerationService;
import com.secai.service.QuestionnaireService;
import com.secai.service.QuestionReviewService;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Phase 5 REPLACEMENT for QuestionnaireController.
 *
 * New endpoints added vs Phase 4:
 *
 *   POST   /api/questionnaires/{id}/generate          ← trigger AI generation
 *   GET    /api/questionnaires/{id}/job               ← poll progress
 *
 *   PUT    /api/questions/{id}                        ← edit an answer
 *   POST   /api/questions/{id}/approve                ← approve an answer
 *   POST   /api/questions/{id}/reject                 ← reject an answer
 *
 * Note: the /api/questions/* endpoints are intentionally on a SEPARATE path
 * (/api/questions instead of /api/questionnaires/{id}/questions/{qid}) to keep
 * the review operations simple — question ID alone is sufficient since we
 * verify org ownership in the service layer via TenantContext.
 *
 * REPLACE the existing QuestionnaireController.java with this file.
 */
@RestController
public class QuestionnaireController {

    private final QuestionnaireService    questionnaireService;
    private final AnswerGenerationService generationService;
    private final QuestionReviewService   reviewService;

    // Injected for job polling (lightweight — avoids loading the full service)
    private final com.secai.domain.questionnaire.AiGenerationJobRepository jobRepo;

    public QuestionnaireController(
            QuestionnaireService    questionnaireService,
            AnswerGenerationService generationService,
            QuestionReviewService   reviewService,
            com.secai.domain.questionnaire.AiGenerationJobRepository jobRepo
    ) {
        this.questionnaireService = questionnaireService;
        this.generationService    = generationService;
        this.reviewService        = reviewService;
        this.jobRepo              = jobRepo;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QUESTIONNAIRE ENDPOINTS (unchanged from Phase 4 + new Phase 5 ones)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/questionnaires
     * Upload and parse a questionnaire file (XLSX, CSV, DOCX).
     */
    @PostMapping(value = "/api/questionnaires", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<QuestionnaireUploadResponse> upload(
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(questionnaireService.uploadAndParse(file));
    }

    /**
     * GET /api/questionnaires
     * List all questionnaires for the authenticated org.
     */
    @GetMapping("/api/questionnaires")
    public ResponseEntity<List<QuestionnaireListResponse>> list() {
        return ResponseEntity.ok(questionnaireService.listForCurrentOrg());
    }

    /**
     * GET /api/questionnaires/{id}
     * Questionnaire detail including status counts and AI job summary.
     */
    @GetMapping("/api/questionnaires/{id}")
    public ResponseEntity<QuestionnaireDetailResponse> getDetail(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(questionnaireService.getDetail(id));
    }

    /**
     * GET /api/questionnaires/{id}/questions?status=PENDING&page=0&size=50
     * Paginated question list. Optional status filter.
     */
    @GetMapping("/api/questionnaires/{id}/questions")
    public ResponseEntity<Page<QuestionResponse>> getQuestions(
            @PathVariable UUID id,
            @RequestParam(required = false)    String status,
            @RequestParam(defaultValue = "0")  int    page,
            @RequestParam(defaultValue = "50") int    size
    ) {
        int cappedSize = Math.min(size, 100);
        return ResponseEntity.ok(questionnaireService.getQuestions(id, status, page, cappedSize));
    }

    /**
     * DELETE /api/questionnaires/{id}
     */
    @DeleteMapping("/api/questionnaires/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        questionnaireService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE 5 — AI GENERATION ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/questionnaires/{id}/generate
     *
     * Triggers AI answer generation for all PENDING questions in the questionnaire.
     * Returns immediately with the current job state (async work runs in background).
     *
     * Idempotent: calling again while RUNNING returns the current job without
     * restarting. Calling again after COMPLETED also returns the existing job.
     * To re-run, the frontend must offer a "Re-generate" flow (not in Phase 5 scope).
     */
    @PostMapping("/api/questionnaires/{id}/generate")
    public ResponseEntity<GenerationJobResponse> generate(@PathVariable UUID id) {
        UUID orgId = TenantContext.get();
        AiGenerationJob job = generationService.startGeneration(id, orgId);
        return ResponseEntity.ok(GenerationJobResponse.from(job));
    }

    /**
     * GET /api/questionnaires/{id}/job
     *
     * Lightweight polling endpoint — returns just the AI job status + progress.
     * Frontend polls this every 2 seconds while status is RUNNING.
     *
     * Returns 404 if no job exists yet (questionnaire was just uploaded but
     * generation has never been triggered — this should not normally happen
     * since we create the job record on questionnaire upload in Phase 4).
     */
    @GetMapping("/api/questionnaires/{id}/job")
    public ResponseEntity<GenerationJobResponse> getJob(@PathVariable UUID id) {
        AiGenerationJob job = jobRepo.findByQuestionnaireId(id)
                .orElseThrow(() -> new NotFoundException("No generation job found for questionnaire " + id));
        return ResponseEntity.ok(GenerationJobResponse.from(job));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PHASE 5 — QUESTION REVIEW ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * PUT /api/questions/{id}
     * Edit an AI-generated answer. Sets status → EDITED.
     * Body: { "manualAnswer": "..." }
     */
    @PutMapping("/api/questions/{id}")
    public ResponseEntity<QuestionResponse> editAnswer(
            @PathVariable UUID id,
            @RequestBody  QuestionAnswerUpdateRequest req
    ) {
        return ResponseEntity.ok(reviewService.editAnswer(id, req));
    }

    /**
     * POST /api/questions/{id}/approve
     * Approve the current answer (AI or edited). Sets status → APPROVED.
     */
    @PostMapping("/api/questions/{id}/approve")
    public ResponseEntity<QuestionResponse> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(reviewService.approve(id));
    }

    /**
     * POST /api/questions/{id}/reject
     * Reject the AI answer. Sets status → REJECTED.
     */
    @PostMapping("/api/questions/{id}/reject")
    public ResponseEntity<QuestionResponse> reject(@PathVariable UUID id) {
        return ResponseEntity.ok(reviewService.reject(id));
    }
}