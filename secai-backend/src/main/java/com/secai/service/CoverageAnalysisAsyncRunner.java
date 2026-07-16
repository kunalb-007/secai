package com.secai.service;

import com.secai.domain.coverage.CoverageReportRepository;
import com.secai.domain.questionnaire.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Extracted async runner for coverage analysis.
 * Separate bean so @Async proxy works correctly (self-invocation fix).
 */
@Component
public class CoverageAnalysisAsyncRunner {

    private static final Logger log = LoggerFactory.getLogger(CoverageAnalysisAsyncRunner.class);

    private final CoverageReportRepository coverageRepo;
    private final QuestionRepository       questionRepo;
    private final CoverageAnalysisService  coverageService;

    public CoverageAnalysisAsyncRunner(
            CoverageReportRepository coverageRepo,
            QuestionRepository       questionRepo,
            CoverageAnalysisService  coverageService
    ) {
        this.coverageRepo   = coverageRepo;
        this.questionRepo   = questionRepo;
        this.coverageService = coverageService;
    }

    @Async
    public void runAsync(UUID reportId, UUID questionnaireId, UUID orgId) {
        log.info("[coverage:{}] Async analysis started on thread: {}",
                reportId, Thread.currentThread().getName());
        coverageService.runAnalysis(reportId, questionnaireId, orgId);
    }
}