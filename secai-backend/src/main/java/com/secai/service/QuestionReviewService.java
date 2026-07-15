package com.secai.service;

import com.secai.config.TenantContext;
import com.secai.domain.questionnaire.*;
import com.secai.dto.questionnaire.QuestionAnswerUpdateRequest;
import com.secai.dto.questionnaire.QuestionResponse;
import com.secai.exception.ForbiddenException;
import com.secai.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class QuestionReviewService {

    private final QuestionRepository      questionRepo;
    private final QuestionnaireRepository questionnaireRepo;
    private final AnswerLibraryService    libraryService;   // ← NEW injection

    public QuestionReviewService(
            QuestionRepository      questionRepo,
            QuestionnaireRepository questionnaireRepo,
            AnswerLibraryService    libraryService             // ← NEW injection
    ) {
        this.questionRepo      = questionRepo;
        this.questionnaireRepo = questionnaireRepo;
        this.libraryService    = libraryService;
    }

    // ── Edit ──────────────────────────────────────────────────────────────────

    @Transactional
    public QuestionResponse editAnswer(UUID questionId, QuestionAnswerUpdateRequest req) {
        Question question = loadAndVerify(questionId);

        if (req.manualAnswer() == null || req.manualAnswer().isBlank()) {
            throw new IllegalArgumentException("Answer cannot be blank.");
        }
        question.setManualAnswer(req.manualAnswer().trim());
        question.setStatus(QuestionStatus.EDITED);
        questionRepo.save(question);

        log.info("Reviewer edited answer for question {}",
                questionId);

        return toResponse(question);
    }

    // ── Approve ───────────────────────────────────────────────────────────────

    @Transactional
    public QuestionResponse approve(UUID questionId) {
        UUID orgId = TenantContext.get();                    // capture before async
        Question question = loadAndVerify(questionId);

        if (question.getStatus() == QuestionStatus.PENDING) {
            throw new IllegalStateException("Cannot approve a question with no AI answer yet.");
        }
        question.setStatus(QuestionStatus.APPROVED);
        questionRepo.save(question);

        log.info("Question {} approved",
                questionId);

        log.info("Submitting approved answer for library indexing: {}",
                questionId);

        // ── NEW: index in library (async — never blocks the HTTP response) ──
        String approverEmail = TenantContext.getEmail();     // see TenantContext update below
        libraryService.indexApprovedAnswer(questionId, approverEmail, orgId);
        // ────────────────────────────────────────────────────────────────────

        return toResponse(question);
    }

    // ── Reject ────────────────────────────────────────────────────────────────

    @Transactional
    public QuestionResponse reject(UUID questionId) {
        Question question = loadAndVerify(questionId);

        question.setStatus(QuestionStatus.REJECTED);
        question.setManualAnswer(null);
        questionRepo.save(question);

        log.info("Question {} rejected",
                questionId);

        return toResponse(question);
    }

    // ── Bulk approve ──────────────────────────────────────────────────────────

    @Transactional
    public int bulkApprove(List<UUID> questionIds) {
        UUID orgId = TenantContext.get();
        String approverEmail = TenantContext.getEmail();     // ← NEW
        int approved = 0;

        for (UUID id : questionIds) {
            Question q = questionRepo.findById(id)
                    .orElseThrow(() -> new NotFoundException("Question not found: " + id));

            if (!orgId.equals(q.getOrganizationId())) {
                throw new ForbiddenException("Access denied to question: " + id);
            }

            if (q.getStatus() == QuestionStatus.GENERATED
                    || q.getStatus() == QuestionStatus.EDITED) {
                q.setStatus(QuestionStatus.APPROVED);
                questionRepo.save(q);
                approved++;

                // ── NEW: index each bulk-approved answer in library ──────────
                libraryService.indexApprovedAnswer(id, approverEmail, orgId);
                // ─────────────────────────────────────────────────────────────
            }
        }

        log.info("Bulk approved {} questions",
                approved);

        return approved;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Question loadAndVerify(UUID questionId) {
        UUID orgId = TenantContext.get();
        Question question = questionRepo.findById(questionId)
                .orElseThrow(() -> new NotFoundException("Question not found"));
        if (!orgId.equals(question.getOrganizationId())) {
            throw new ForbiddenException("Access denied to this question");
        }
        return question;
    }

    private QuestionResponse toResponse(Question q) {
        return new QuestionResponse(
                q.getId(), q.getQuestionNumber(), q.getQuestionText(),
                q.getCategory(), q.getAiAnswer(), q.getEvidence(),
                q.getRetrievalScore(), q.getStatus(), q.getManualAnswer(),
                q.getSortOrder()
        );
    }
}