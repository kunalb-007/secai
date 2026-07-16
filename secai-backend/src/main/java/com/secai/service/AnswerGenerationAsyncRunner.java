package com.secai.service;

import com.secai.domain.questionnaire.*;
import com.secai.domain.questionnaire.AiGenerationJob.AiJobStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Separate Spring bean for @Async dispatch of answer generation.
 * Must be a distinct bean from AnswerGenerationService — if the same class
 * calls its own @Async method, Spring's proxy is bypassed and the method
 * runs synchronously on the HTTP thread.
 */
@Slf4j
@Component
public class AnswerGenerationAsyncRunner {

    /**
     * Save job progress to DB every N questions instead of every question.
     * 400 questions → 40 DB writes instead of 400.
     * Frontend polls every 5 seconds; 10-question granularity is invisible to users.
     */
    private static final int PROGRESS_SAVE_EVERY = 10;

    private final QuestionRepository        questionRepo;
    private final AiGenerationJobRepository jobRepo;
    private final QuestionnaireRepository   questionnaireRepo;
    private final AnswerGenerationService   generationService;

    public AnswerGenerationAsyncRunner(
            QuestionRepository        questionRepo,
            AiGenerationJobRepository jobRepo,
            QuestionnaireRepository   questionnaireRepo,
            AnswerGenerationService   generationService
    ) {
        this.questionRepo       = questionRepo;
        this.jobRepo            = jobRepo;
        this.questionnaireRepo  = questionnaireRepo;
        this.generationService  = generationService;
    }

    @Async
    public void runAsync(UUID jobId, UUID questionnaireId, UUID orgId) {
        log.info("[job:{}] Generation started on thread: {}", jobId, Thread.currentThread().getName());

        AiGenerationJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) {
            log.error("[job:{}] Job not found — aborting", jobId);
            return;
        }

        try {
            List<Question> questions =
                    questionRepo.findByQuestionnaireIdAndOrganizationIdOrderBySortOrder(
                            questionnaireId, orgId
                    );

            log.info("[job:{}] Processing {} questions", jobId, questions.size());

            int completed = 0;

            for (int i = 0; i < questions.size(); i++) {
                Question question = questions.get(i);
                try {
                    generationService.generateAnswer(question, orgId);
                } catch (Exception e) {
                    log.error("[job:{}] Failed on question {}: {}", jobId, question.getId(), e.getMessage());
                    generationService.markQuestionFailed(question);
                }
                completed++;

                // Save progress every PROGRESS_SAVE_EVERY questions, and always on the last one.
                // Reduces DB writes from 400 to ~40 for a typical questionnaire.
                if (completed % PROGRESS_SAVE_EVERY == 0 || i == questions.size() - 1) {
                    job.setCompletedQuestions(completed);
                    jobRepo.save(job);
                    log.info("[job:{}] Progress {}/{}", jobId, completed, questions.size());
                }
            }

            job.setStatus(AiJobStatus.COMPLETED);
            job.setFinishedAt(OffsetDateTime.now());
            job.setCompletedQuestions(completed);
            jobRepo.save(job);

            questionnaireRepo.findById(questionnaireId).ifPresent(q -> {
                q.setStatus(QuestionnaireStatus.COMPLETED);
                questionnaireRepo.save(q);
            });

            log.info("[job:{}] COMPLETED — {} questions answered", jobId, completed);

        } catch (Exception e) {
            log.error("[job:{}] Fatal error: {}", jobId, e.getMessage(), e);
            job.setStatus(AiJobStatus.FAILED);
            job.setFinishedAt(OffsetDateTime.now());
            jobRepo.save(job);

            questionnaireRepo.findById(questionnaireId).ifPresent(q -> {
                q.setStatus(QuestionnaireStatus.FAILED);
                questionnaireRepo.save(q);
            });
        }
    }
}