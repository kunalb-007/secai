package com.secai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables @Async and @Retryable annotations across the application.
 */
@Configuration
@EnableAsync
@EnableRetry
public class AsyncConfig {
    // Spring Boot auto-configures the thread pool from application.yml
    // spring.task.execution.pool.* settings
}