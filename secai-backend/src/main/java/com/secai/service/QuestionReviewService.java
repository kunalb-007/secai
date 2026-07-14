package com.secai.service;

import com.secai.config.TenantContext;
import com.secai.domain.questionnaire.*;
import com.secai.dto.questionnaire.QuestionAnswerUpdateRequest;
import com.secai.dto.questionnaire.QuestionResponse;
import com.secai.exception.ForbiddenException;
import com.secai.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Phase 6 — Human review: edit, approve, reject, bulk-approve.
 *
 * Every mutating method validates org ownership via TenantContext before acting.
 * Individual question status transitions:
 *   GENERATED → EDITED    (edit)
 *   GENERATED → APPROVED  (approve)
 *   GENERATED → REJECTED  (reject)
 *   EDITED    → APPROVED  (approve after edit)
 *   EDITED    → REJECTED  (reject)
 *   REJECTED  → EDITED    (re-edit a rejection)
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

    // ── Single-question actions ────────────────────────────────────────────────

    @Transactional
    public QuestionResponse editAnswer(UUID questionId, QuestionAnswerUpdateRequest req) {
        Question question = loadAndVerify(questionId);

        if (req.manualAnswer() == null || req.manualAnswer().isBlank()) {
            throw new IllegalArgumentException("Answer cannot be blank.");
        }

        question.setManualAnswer(req.manualAnswer().trim());
        question.setStatus(QuestionStatus.EDITED);
        questionRepo.save(question);
        return toResponse(question);
    }

    @Transactional
    public QuestionResponse approve(UUID questionId) {
        Question question = loadAndVerify(questionId);

        if (question.getStatus() == QuestionStatus.PENDING) {
            throw new IllegalStateException("Cannot approve a question that has no AI answer yet.");
        }
        question.setStatus(QuestionStatus.APPROVED);
        questionRepo.save(question);
        return toResponse(question);
    }

    @Transactional
    public QuestionResponse reject(UUID questionId) {
        Question question = loadAndVerify(questionId);

        question.setStatus(QuestionStatus.REJECTED);
        question.setManualAnswer(null);
        questionRepo.save(question);
        return toResponse(question);
    }

    // ── Bulk actions ──────────────────────────────────────────────────────────

    /**
     * Bulk-approve a list of question IDs in a single transaction.
     * Only GENERATED or EDITED questions are approved; others are silently skipped.
     * All IDs must belong to the current org — any mismatch throws ForbiddenException.
     *
     * @return count of questions actually approved
     */
    @Transactional
    public int bulkApprove(List<UUID> questionIds) {
        UUID orgId = TenantContext.get();
        int approved = 0;

        for (UUID id : questionIds) {
            Question q = questionRepo.findById(id)
                    .orElseThrow(() -> new NotFoundException("Question not found: " + id));

            if (!orgId.equals(q.getOrganizationId())) {
                throw new ForbiddenException("Access denied to question: " + id);
            }

            // Only approve answerable statuses — skip PENDING / already APPROVED
            if (q.getStatus() == QuestionStatus.GENERATED
                    || q.getStatus() == QuestionStatus.EDITED) {
                q.setStatus(QuestionStatus.APPROVED);
                questionRepo.save(q);
                approved++;
            }
        }

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