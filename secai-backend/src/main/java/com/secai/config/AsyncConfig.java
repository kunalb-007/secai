package com.secai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enables @Async and @Retryable annotations.
 * Also declares TransactionTemplate for programmatic short-lived transactions
 * in AnswerGenerationService (avoids holding DB connections during LLM calls).
 */
@Configuration
@EnableAsync
@EnableRetry
public class AsyncConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}