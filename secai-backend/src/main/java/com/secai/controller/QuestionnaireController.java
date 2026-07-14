package com.secai.controller;

import com.secai.config.TenantContext;
import com.secai.domain.questionnaire.AiGenerationJob;
import com.secai.domain.questionnaire.AiGenerationJobRepository;
import com.secai.dto.questionnaire.*;
import com.secai.exception.NotFoundException;
import com.secai.service.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6 & 7 replacement for QuestionnaireController.
 *
 * New vs Phase 5:
 *   POST /api/questionnaires/{id}/questions/bulk-approve  ← Phase 6 bulk action
 *   GET  /api/questionnaires/{id}/export                  ← Phase 7 Excel export
 */
@RestController
public class QuestionnaireController {

    private final QuestionnaireService    questionnaireService;
    private final AnswerGenerationService generationService;
    private final QuestionReviewService   reviewService;
    private final ExportService           exportService;
    private final AiGenerationJobRepository jobRepo;

    public QuestionnaireController(
            QuestionnaireService    questionnaireService,
            AnswerGenerationService generationService,
            QuestionReviewService   reviewService,
            ExportService           exportService,
            AiGenerationJobRepository jobRepo
    ) {
        this.questionnaireService = questionnaireService;
        this.generationService    = generationService;
        this.reviewService        = reviewService;
        this.exportService        = exportService;
        this.jobRepo              = jobRepo;
    }

    // ── Questionnaire CRUD ────────────────────────────────────────────────────

    @PostMapping(value = "/api/questionnaires", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<QuestionnaireUploadResponse> upload(
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionnaireService.uploadAndParse(file));
    }

    @GetMapping("/api/questionnaires")
    public ResponseEntity<List<QuestionnaireListResponse>> list() {
        return ResponseEntity.ok(questionnaireService.listForCurrentOrg());
    }

    @GetMapping("/api/questionnaires/{id}")
    public ResponseEntity<QuestionnaireDetailResponse> getDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(questionnaireService.getDetail(id));
    }

    @GetMapping("/api/questionnaires/{id}/questions")
    public ResponseEntity<Page<QuestionResponse>> getQuestions(
            @PathVariable UUID id,
            @RequestParam(required = false)    String status,
            @RequestParam(required = false)    String filter,   // alias for status (Phase 6 spec)
            @RequestParam(defaultValue = "0")  int    page,
            @RequestParam(defaultValue = "50") int    size
    ) {
        // Accept both ?status= and ?filter= — Phase 6 spec uses "filter"
        String effectiveFilter = (status != null && !status.isBlank()) ? status
                : (filter != null && !filter.isBlank()) ? normaliseFilter(filter)
                  : null;

        int capped = Math.min(size, 200);
        return ResponseEntity.ok(
                questionnaireService.getQuestions(id, effectiveFilter, page, capped)
        );
    }

    @DeleteMapping("/api/questionnaires/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        questionnaireService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── AI generation ─────────────────────────────────────────────────────────

    @PostMapping("/api/questionnaires/{id}/generate")
    public ResponseEntity<GenerationJobResponse> generate(@PathVariable UUID id) {
        UUID orgId = TenantContext.get();
        AiGenerationJob job = generationService.startGeneration(id, orgId);
        return ResponseEntity.ok(GenerationJobResponse.from(job));
    }

    @GetMapping("/api/questionnaires/{id}/job")
    public ResponseEntity<GenerationJobResponse> getJob(@PathVariable UUID id) {
        AiGenerationJob job = jobRepo.findByQuestionnaireId(id)
                .orElseThrow(() -> new NotFoundException("No generation job found for questionnaire " + id));
        return ResponseEntity.ok(GenerationJobResponse.from(job));
    }

    // ── Phase 7: Export ───────────────────────────────────────────────────────

    /**
     * GET /api/questionnaires/{id}/export
     *
     * Streams an .xlsx file to the browser.
     * Content-Disposition: attachment triggers a download in every browser.
     * Filename is derived from the questionnaire's original filename.
     */
    @GetMapping("/api/questionnaires/{id}/export")
    public void export(
            @PathVariable UUID id,
            HttpServletResponse response
    ) throws IOException {
        byte[] xlsx = exportService.export(id);

        // Build a safe filename: "answers_<original_name>.xlsx"
        String detail = questionnaireService.getDetail(id).filename();
        String safeName = "answers_" + detail.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (!safeName.toLowerCase().endsWith(".xlsx")) {
            safeName = safeName.replaceAll("\\.[^.]+$", "") + ".xlsx";
        }

        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + safeName + "\"; filename*=UTF-8''"
                        + URLEncoder.encode(safeName, StandardCharsets.UTF_8)
        );
        response.setContentLength(xlsx.length);
        response.getOutputStream().write(xlsx);
    }

    // ── Phase 6: Single-question review ──────────────────────────────────────

    @PutMapping("/api/questions/{id}")
    public ResponseEntity<QuestionResponse> editAnswer(
            @PathVariable UUID id,
            @RequestBody  QuestionAnswerUpdateRequest req
    ) {
        return ResponseEntity.ok(reviewService.editAnswer(id, req));
    }

    @PostMapping("/api/questions/{id}/approve")
    public ResponseEntity<QuestionResponse> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(reviewService.approve(id));
    }

    @PostMapping("/api/questions/{id}/reject")
    public ResponseEntity<QuestionResponse> reject(@PathVariable UUID id) {
        return ResponseEntity.ok(reviewService.reject(id));
    }

    // ── Phase 6: Bulk approve ─────────────────────────────────────────────────

    /**
     * POST /api/questionnaires/{id}/questions/bulk-approve
     * Body: { "questionIds": ["uuid1", "uuid2", ...] }
     * Returns: { "approved": 42 }
     */
    @PostMapping("/api/questionnaires/{id}/questions/bulk-approve")
    public ResponseEntity<BulkApproveResponse> bulkApprove(
            @PathVariable UUID id,
            @RequestBody  BulkApproveRequest req
    ) {
        int count = reviewService.bulkApprove(req.questionIds());
        return ResponseEntity.ok(new BulkApproveResponse(count));
    }

    // ── Inner DTOs for bulk approve (small enough to stay inline) ────────────

    public record BulkApproveRequest(List<UUID> questionIds) {}
    public record BulkApproveResponse(int approved) {}

    // ── Filter name normaliser ────────────────────────────────────────────────

    /**
     * Maps friendly filter names used in the UI to QuestionStatus enum values.
     *   "low_confidence" → "GENERATED"  (we can't filter by score server-side yet;
     *                                    the low-conf filter is client-side)
     *   "pending"        → "PENDING"
     *   "approved"       → "APPROVED"
     *   "edited"         → "EDITED"
     *   "rejected"       → "REJECTED"
     */
    private String normaliseFilter(String filter) {
        return switch (filter.toLowerCase()) {
            case "pending"        -> "PENDING";
            case "approved"       -> "APPROVED";
            case "edited"         -> "EDITED";
            case "rejected"       -> "REJECTED";
            case "generated"      -> "GENERATED";
            case "low_confidence" -> "GENERATED";  // client handles score filter
            default               -> filter.toUpperCase();
        };
    }
}