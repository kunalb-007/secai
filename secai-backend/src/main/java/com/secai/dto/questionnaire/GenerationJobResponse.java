package com.secai.dto.questionnaire;

import com.secai.domain.questionnaire.AiGenerationJob.AiJobStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO returned by GET /api/questionnaires/{id}/job and
 * POST /api/questionnaires/{id}/generate.
 *
 * Used by the frontend to poll generation progress and render the progress bar.
 */
public record GenerationJobResponse(
        UUID            jobId,
        UUID            questionnaireId,
        AiJobStatus     status,
        int             totalQuestions,
        int             completedQuestions,

        /**
         * Progress as a percentage (0–100), computed server-side for convenience.
         */
        int             progressPercent,

        OffsetDateTime  startedAt,
        OffsetDateTime  finishedAt,

        /**
         * Human-readable status message for display in the progress bar.
         * Examples:
         *   "Generating… 157 / 412 answered"
         *   "Generation complete — 412 questions answered"
         *   "Generation failed. Please try again."
         */
        String          statusMessage
) {
    /**
     * Factory method — computes derived fields from the raw job entity.
     */
    public static GenerationJobResponse from(com.secai.domain.questionnaire.AiGenerationJob job) {
        int percent = job.getTotalQuestions() == 0
                ? 0
                : (int) Math.round((job.getCompletedQuestions() * 100.0) / job.getTotalQuestions());

        String message = switch (job.getStatus()) {
            case PENDING   -> "Ready to generate answers. Click 'Generate Answers' to start.";
            case RUNNING   -> String.format("Generating… %d / %d answered",
                    job.getCompletedQuestions(), job.getTotalQuestions());
            case COMPLETED -> String.format("Generation complete — %d questions answered",
                    job.getCompletedQuestions());
            case FAILED    -> "Generation failed. Please try again.";
        };

        return new GenerationJobResponse(
                job.getId(),
                job.getQuestionnaireId(),
                job.getStatus(),
                job.getTotalQuestions(),
                job.getCompletedQuestions(),
                percent,
                job.getStartedAt(),
                job.getFinishedAt(),
                message
        );
    }
}