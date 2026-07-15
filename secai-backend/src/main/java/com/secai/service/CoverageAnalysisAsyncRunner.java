package com.secai.service;

import com.secai.domain.coverage.CoverageReport;
import com.secai.domain.coverage.CoverageReport.CategoryCoverage;
import com.secai.domain.coverage.CoverageReportRepository;
import com.secai.domain.document.DocumentChunkRepository;
import com.secai.domain.questionnaire.Question;
import com.secai.domain.questionnaire.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

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