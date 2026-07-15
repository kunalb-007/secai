package com.secai.service;

import com.secai.domain.questionnaire.*;
import com.secai.domain.questionnaire.AiGenerationJob.AiJobStatus;
import com.secai.domain.questionnaire.QuestionnaireStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Extracted async runner for answer generation.
 * Separate bean so @Async proxy works correctly (self-invocation fix).
 */
@Slf4j
@Component
public class AnswerGenerationAsyncRunner {

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
        log.info("[job:{}] Async generation started on thread: {}",
                jobId, Thread.currentThread().getName());

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

            log.info("[job:{}] Loaded {} questions for processing", jobId, questions.size());

            int completed = 0;

            for (Question question : questions) {
                try {
                    generationService.generateAnswer(question, orgId);
                    completed++;
                    log.info("[job:{}] Progress {}/{}", jobId, completed, questions.size());
                } catch (Exception e) {
                    log.error("[job:{}] Failed to answer question {}: {}",
                            jobId, question.getId(), e.getMessage());
                    generationService.markQuestionFailed(question);
                    completed++;
                }

                job.setCompletedQuestions(completed);
                jobRepo.save(job);
            }

            job.setStatus(AiJobStatus.COMPLETED);
            job.setFinishedAt(OffsetDateTime.now());
            job.setCompletedQuestions(completed);
            jobRepo.save(job);

            questionnaireRepo.findById(questionnaireId).ifPresent(q -> {
                q.setStatus(QuestionnaireStatus.COMPLETED);
                questionnaireRepo.save(q);
            });

            log.info("[job:{}] Generation COMPLETED. {} questions answered.", jobId, completed);

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