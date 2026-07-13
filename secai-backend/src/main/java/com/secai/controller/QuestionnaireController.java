package com.secai.controller;

import com.secai.dto.questionnaire.*;
import com.secai.service.QuestionnaireService;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/questionnaires")
public class QuestionnaireController {

    private final QuestionnaireService questionnaireService;

    public QuestionnaireController(QuestionnaireService questionnaireService) {
        this.questionnaireService = questionnaireService;
    }

    /**
     * POST /api/questionnaires
     * Upload and parse a questionnaire file (XLSX, CSV, DOCX).
     * Returns parsed question count + first 10 questions as preview.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
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
    @GetMapping
    public ResponseEntity<List<QuestionnaireListResponse>> list() {
        return ResponseEntity.ok(questionnaireService.listForCurrentOrg());
    }

    /**
     * GET /api/questionnaires/{id}
     * Get questionnaire detail with status counts and AI job summary.
     */
    @GetMapping("/{id}")
    public ResponseEntity<QuestionnaireDetailResponse> getDetail(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(questionnaireService.getDetail(id));
    }

    /**
     * GET /api/questionnaires/{id}/questions?status=PENDING&page=0&size=50
     * Paginated list of questions. Optional status filter.
     */
    @GetMapping("/{id}/questions")
    public ResponseEntity<Page<QuestionResponse>> getQuestions(
            @PathVariable UUID id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        // Cap page size to avoid huge responses
        int cappedSize = Math.min(size, 100);
        return ResponseEntity.ok(questionnaireService.getQuestions(id, status, page, cappedSize));
    }

    /**
     * DELETE /api/questionnaires/{id}
     * Delete questionnaire and all its questions.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        questionnaireService.delete(id);
        return ResponseEntity.noContent().build();
    }
}