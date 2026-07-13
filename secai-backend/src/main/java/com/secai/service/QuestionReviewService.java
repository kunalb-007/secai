package com.secai.service;

import com.secai.config.TenantContext;
import com.secai.domain.questionnaire.*;
import com.secai.dto.questionnaire.QuestionAnswerUpdateRequest;
import com.secai.dto.questionnaire.QuestionResponse;
import com.secai.exception.ForbiddenException;
import com.secai.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Phase 5 — Human review operations on individual questions.
 *
 * Supports:
 *   - Editing an AI answer (saves as EDITED, preserves ai_answer for reference)
 *   - Approving an answer   (status → APPROVED)
 *   - Rejecting an answer   (status → REJECTED, clears manual_answer)
 *
 * Every operation validates org ownership via TenantContext before mutating.
 */
@Service
public class QuestionReviewService {

    private final QuestionRepository      questionRepo;
    private final QuestionnaireRepository questionnaireRepo;

    public QuestionReviewService(
            QuestionRepository      questionRepo,
            QuestionnaireRepository questionnaireRepo
    ) {
        this.questionRepo      = questionRepo;
        this.questionnaireRepo = questionnaireRepo;
    }

    // ── Edit ──────────────────────────────────────────────────────────────────

    /**
     * PUT /api/questions/{id}
     * Saves a manual answer. Sets status → EDITED.
     * The original ai_answer is preserved for audit purposes.
     */
    @Transactional
    public QuestionResponse editAnswer(UUID questionId, QuestionAnswerUpdateRequest req) {
        Question question = loadAndVerify(questionId);

        if (req.manualAnswer() == null || req.manualAnswer().isBlank()) {
            throw new IllegalArgumentException("Manual answer cannot be blank.");
        }

        question.setManualAnswer(req.manualAnswer().trim());
        question.setStatus(QuestionStatus.EDITED);
        questionRepo.save(question);

        return toResponse(question);
    }

    // ── Approve ───────────────────────────────────────────────────────────────

    /**
     * POST /api/questions/{id}/approve
     * Approves the current answer (AI or edited). Sets status → APPROVED.
     */
    @Transactional
    public QuestionResponse approve(UUID questionId) {
        Question question = loadAndVerify(questionId);

        // Can only approve GENERATED or EDITED questions
        if (question.getStatus() != QuestionStatus.GENERATED
                && question.getStatus() != QuestionStatus.EDITED) {
            throw new IllegalStateException(
                    "Only generated or edited answers can be approved."
            );
        }

        question.setStatus(QuestionStatus.APPROVED);
        questionRepo.save(question);

        return toResponse(question);
    }

    // ── Reject ────────────────────────────────────────────────────────────────

    /**
     * POST /api/questions/{id}/reject
     * Rejects the AI answer. Sets status → REJECTED.
     * Manual answer is cleared so it won't appear in the export.
     */
    @Transactional
    public QuestionResponse reject(UUID questionId) {
        Question question = loadAndVerify(questionId);

        if (question.getStatus() == QuestionStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot reject a question that has not been answered by AI yet."
            );
        }

        question.setStatus(QuestionStatus.REJECTED);
        question.setManualAnswer(null);
        questionRepo.save(question);

        return toResponse(question);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Loads a question and verifies it belongs to the current org.
     * Throws NotFoundException or ForbiddenException appropriately.
     */
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
                q.getId(),
                q.getQuestionNumber(),
                q.getQuestionText(),
                q.getCategory(),
                q.getAiAnswer(),
                q.getEvidence(),
                q.getRetrievalScore(),
                q.getStatus(),
                q.getManualAnswer(),
                q.getSortOrder()
        );
    }
}