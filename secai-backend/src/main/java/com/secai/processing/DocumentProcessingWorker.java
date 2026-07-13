package com.secai.processing;

import com.secai.domain.document.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Async entry point for document processing.
 *
 * Separated from DocumentProcessingService to allow:
 * 1. @Async to work correctly (Spring proxy requirement — must call from outside the bean)
 * 2. Retry logic with clean recovery (mark FAILED after max attempts)
 *
 * Retry policy (per MVP plan):
 * - Max 1 retry (2 total attempts)
 * - 3-second delay between attempts
 * - On final failure: status = FAILED, error_message set for UI display
 */
@Component
public class DocumentProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingWorker.class);

    private final DocumentProcessingService processingService;
    private final DocumentRepository        documentRepo;

    public DocumentProcessingWorker(
            DocumentProcessingService processingService,
            DocumentRepository        documentRepo
    ) {
        this.processingService = processingService;
        this.documentRepo      = documentRepo;
    }

    /**
     * Dispatches document processing asynchronously.
     * Called by DocumentService immediately after upload.
     *
     * @Retryable: retry once (maxAttempts=2) on any Exception,
     * with 3s delay. If still failing after 2 attempts, @Recover kicks in.
     */
    @Async
    @Retryable(
            maxAttempts = 2,
            backoff = @Backoff(delay = 3000),
            noRetryFor = IllegalArgumentException.class   // don't retry bad file type errors
    )
    public void processAsync(UUID documentId) throws Exception {
        log.info("[{}] Async processing started on thread: {}",
                documentId, Thread.currentThread().getName());
        processingService.process(documentId);
    }

    /**
     * Called by Spring Retry after all attempts are exhausted.
     * Sets document status to FAILED with a user-friendly error message.
     */
    @Recover
    public void onProcessingFailed(Exception ex, UUID documentId) {
        log.error("[{}] All retry attempts failed. Marking FAILED. Cause: {}",
                documentId, ex.getMessage());

        documentRepo.findById(documentId).ifPresent(doc -> {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setProcessedAt(OffsetDateTime.now());

            // Set a user-friendly message (not the raw stack trace)
            String friendlyMessage = buildFriendlyErrorMessage(ex);
            doc.setErrorMessage(friendlyMessage);

            documentRepo.save(doc);
            log.info("[{}] Saved FAILED status with message: {}", documentId, friendlyMessage);
        });
    }

    private String buildFriendlyErrorMessage(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) return "Processing failed. Please try again or convert to TXT.";

        if (msg.contains("Marker service")) {
            return "Unable to process this PDF. The file may be scanned or image-only. "
                    + "Try converting to TXT or DOCX format.";
        }
        if (msg.contains("empty") || msg.contains("zero chunks")) {
            return "Document appears to be empty or contains no extractable text.";
        }
        if (msg.contains("OpenAI") || msg.contains("embedding")) {
            return "AI service temporarily unavailable. Please try uploading again.";
        }
        return "Processing failed. Please try again or contact support.";
    }
}