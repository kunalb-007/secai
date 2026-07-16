package com.secai.service;

import com.secai.config.TenantContext;
import com.secai.domain.questionnaire.*;
import com.secai.domain.questionnaire.AiGenerationJob.AiJobStatus;
import com.secai.dto.questionnaire.QuestionResponse;
import com.secai.dto.questionnaire.QuestionnaireDetailResponse;
import com.secai.dto.questionnaire.QuestionnaireListResponse;
import com.secai.dto.questionnaire.QuestionnaireUploadResponse;
import com.secai.exception.NotFoundException;
import com.secai.parsing.ParseException;
import com.secai.parsing.ParseResult;
import com.secai.parsing.QuestionnaireParser;
import com.secai.parsing.QuestionnaireParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class QuestionnaireService {

    private static final Logger log = LoggerFactory.getLogger(QuestionnaireService.class);

    // Allowed formats for questionnaire upload
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".xlsx", ".csv", ".docx");
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 20MB

    private final QuestionnaireRepository    questionnaireRepo;
    private final QuestionRepository         questionRepo;
    private final AiGenerationJobRepository  jobRepo;
    private final QuestionnaireParserFactory parserFactory;
    private final CoverageAnalysisService coverageAnalysisService;

    public QuestionnaireService(
            QuestionnaireRepository    questionnaireRepo,
            QuestionRepository         questionRepo,
            AiGenerationJobRepository  jobRepo,
            QuestionnaireParserFactory parserFactory,
            CoverageAnalysisService coverageAnalysisService
    ) {
        this.questionnaireRepo = questionnaireRepo;
        this.questionRepo      = questionRepo;
        this.jobRepo           = jobRepo;
        this.parserFactory     = parserFactory;
        this.coverageAnalysisService = coverageAnalysisService;
    }

    // ── Upload & Parse ─────────────────────────────────────────────────────────

    @Transactional
    public QuestionnaireUploadResponse uploadAndParse(MultipartFile file) {
        UUID orgId = TenantContext.get();

        log.info("Questionnaire upload received: {}",
                file.getOriginalFilename());

        // Validate file
        validateFile(file);

        String format   = parserFactory.detectFormat(file.getOriginalFilename());
        String filename = sanitize(file.getOriginalFilename());

        // Parse questions from the file
        ParseResult parseResult;
        try {
            QuestionnaireParser parser = parserFactory.forFile(
                    file.getOriginalFilename(), file.getContentType()
            );
            parseResult = parser.parse(file);
            log.info("Parsed {} questions from {}",
                    parseResult.questions().size(), filename);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Could not parse questionnaire: " + e.getMessage());
        }

        if (parseResult.questions().isEmpty()) {
            throw new IllegalArgumentException(
                    "No questions found in the uploaded file. "
                            + "Please check the format and ensure questions are present."
            );
        }

        // Save Questionnaire record
        Questionnaire q = questionnaireRepo.save(
                Questionnaire.builder()
                        .organizationId(orgId)
                        .filename(filename)
                        .originalFormat(format)
                        .status(QuestionnaireStatus.PARSED)
                        .totalQuestions(parseResult.questions().size())
                        .parseConfidence(parseResult.confidence())
                        .lowConfidenceFlag(parseResult.lowConfidence())
                        .build()
        );

        log.info("Created questionnaire {} for organization {}",
                q.getId(),
                orgId);

        // Save all questions in one batch
        List<Question> questions = parseResult.questions().stream()
                .map(pq -> Question.builder()
                        .questionnaireId(q.getId())
                        .organizationId(orgId)
                        .questionNumber(pq.questionNumber())
                        .questionText(pq.questionText())
                        .category(pq.category())
                        .sortOrder(pq.sortOrder())
                        .status(QuestionStatus.PENDING)
                        .build()
                )
                .toList();
        questionRepo.saveAll(questions);

        log.info("Persisted {} questionnaire questions",
                questions.size());

        // Create AI generation job (status=PENDING — triggered in Phase 5)
        AiGenerationJob job = jobRepo.save(
                AiGenerationJob.builder()
                        .questionnaireId(q.getId())
                        .status(AiJobStatus.PENDING)
                        .totalQuestions(questions.size())
                        .completedQuestions(0)
                        .build()
        );

        log.info("Created AI generation job {} for questionnaire {}",
                job.getId(),
                q.getId());

        log.info("Triggering document coverage analysis for questionnaire {}",
                q.getId());

        // ── NEW: trigger coverage analysis asynchronously ───────────────────────────
        coverageAnalysisService.triggerAnalysis(q.getId(), orgId);

        log.info("Questionnaire upload completed successfully: questionnaire={}, questions={}, generationJob={}",
                q.getId(),
                questions.size(),
                job.getId());

        // Build preview (first 10 questions)
        List<QuestionnaireUploadResponse.QuestionPreview> preview = parseResult.questions()
                .stream()
                .limit(10)
                .map(pq -> new QuestionnaireUploadResponse.QuestionPreview(
                        pq.questionNumber(), pq.questionText(), pq.category()
                ))
                .toList();

        return new QuestionnaireUploadResponse(
                q.getId(),
                q.getFilename(),
                q.getStatus(),
                q.getTotalQuestions(),
                q.isLowConfidenceFlag(),
                parseResult.warningMessage(),
                preview
        );
    }

    // ── List questionnaires ────────────────────────────────────────────────────

    public List<QuestionnaireListResponse> listForCurrentOrg() {
        UUID orgId = TenantContext.get();
        return questionnaireRepo
                .findByOrganizationIdOrderByUploadedAtDesc(orgId)
                .stream()
                .map(this::toListResponse)
                .toList();
    }

    // ── Questionnaire detail ───────────────────────────────────────────────────

    public QuestionnaireDetailResponse getDetail(UUID questionnaireId) {
        UUID orgId = TenantContext.get();
        Questionnaire q = questionnaireRepo
                .findByIdAndOrganizationId(questionnaireId, orgId)
                .orElseThrow(() -> new NotFoundException("Questionnaire not found"));

        // Status counts for the stats strip
        Map<String, Long> statusCounts = questionRepo
                .countByStatus(questionnaireId, orgId)
                .stream()
                .collect(Collectors.toMap(
                        row -> ((QuestionStatus) row[0]).name(),
                        row -> (Long) row[1]
                ));

        // AI job summary
        QuestionnaireDetailResponse.AiJobSummary jobSummary = jobRepo
                .findByQuestionnaireId(questionnaireId)
                .map(job -> new QuestionnaireDetailResponse.AiJobSummary(
                        job.getId(),
                        job.getStatus().name(),
                        job.getTotalQuestions(),
                        job.getCompletedQuestions()
                ))
                .orElse(null);

        String warning = q.isLowConfidenceFlag()
                ? String.format(
                "Parse confidence is low (%.0f%%). Please review all questions and add any missing ones.",
                (q.getParseConfidence() != null ? q.getParseConfidence() : 0.0) * 100)
                : null;

        return new QuestionnaireDetailResponse(
                q.getId(), q.getFilename(), q.getOriginalFormat(),
                q.getStatus(), q.getTotalQuestions(),
                q.isLowConfidenceFlag(), warning,
                statusCounts, q.getUploadedAt(), jobSummary
        );
    }

    // ── Questions (paginated) ─────────────────────────────────────────────────

    public Page<QuestionResponse> getQuestions(
            UUID questionnaireId, String statusFilter, int page, int size
    ) {
        UUID orgId = TenantContext.get();

        // Verify questionnaire belongs to this org
        questionnaireRepo.findByIdAndOrganizationId(questionnaireId, orgId)
                .orElseThrow(() -> new NotFoundException("Questionnaire not found"));

        Pageable pageable = PageRequest.of(page, size);

        Page<Question> result;
        if (statusFilter != null && !statusFilter.isBlank()) {
            QuestionStatus filterStatus = QuestionStatus.valueOf(statusFilter.toUpperCase());
            result = questionRepo
                    .findByQuestionnaireIdAndOrganizationIdAndStatusOrderBySortOrder(
                            questionnaireId, orgId, filterStatus, pageable);
        } else {
            result = questionRepo
                    .findByQuestionnaireIdAndOrganizationIdOrderBySortOrder(
                            questionnaireId, orgId, pageable);
        }

        return result.map(this::toQuestionResponse);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public void delete(UUID questionnaireId) {
        UUID orgId = TenantContext.get();
        Questionnaire q = questionnaireRepo
                .findByIdAndOrganizationId(questionnaireId, orgId)
                .orElseThrow(() -> new NotFoundException("Questionnaire not found"));

        questionRepo.deleteByQuestionnaireIdAndOrganizationId(questionnaireId, orgId);
        questionnaireRepo.delete(q);

        log.info("Deleted questionnaire {}",
                questionnaireId);
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private QuestionnaireListResponse toListResponse(Questionnaire q) {
        return new QuestionnaireListResponse(
                q.getId(), q.getFilename(), q.getOriginalFormat(),
                q.getStatus(), q.getTotalQuestions(),
                q.isLowConfidenceFlag(), q.getUploadedAt()
        );
    }

    private QuestionResponse toQuestionResponse(Question q) {
        return new QuestionResponse(
                q.getId(), q.getQuestionNumber(), q.getQuestionText(),
                q.getCategory(), q.getAiAnswer(), q.getEvidence(),
                q.getRetrievalScore(), q.getStatus(),
                q.getManualAnswer(), q.getSortOrder(),
                q.getSourceChunkId(),      // NEW
                q.getSourceDocumentId()    // NEW
        );
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File exceeds 20MB limit");
        }
        String name = file.getOriginalFilename();
        if (name == null) throw new IllegalArgumentException("Filename missing");

        String lower = name.toLowerCase();
        boolean valid = ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!valid) {
            throw new IllegalArgumentException(
                    "Unsupported format. Questionnaires must be XLSX, CSV, or DOCX. "
                            + "PDF questionnaires are not supported — please ask your customer for the Excel or Word version."
            );
        }
    }

    private String sanitize(String filename) {
        if (filename == null) return "questionnaire";
        return filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}