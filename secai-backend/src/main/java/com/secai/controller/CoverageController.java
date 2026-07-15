package com.secai.controller;

import com.secai.config.TenantContext;
import com.secai.dto.coverage.CoverageReportResponse;
import com.secai.service.CoverageAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Coverage Analysis endpoints.
 *
 *   GET  /api/questionnaires/{id}/coverage         — poll report status + results
 *   POST /api/questionnaires/{id}/coverage/refresh — re-run analysis (after new doc upload)
 */
@RestController
public class CoverageController {

    private final CoverageAnalysisService coverageService;

    public CoverageController(CoverageAnalysisService coverageService) {
        this.coverageService = coverageService;
    }

    @GetMapping("/api/questionnaires/{id}/coverage")
    public ResponseEntity<CoverageReportResponse> getReport(@PathVariable UUID id) {
        UUID orgId = TenantContext.get();
        return ResponseEntity.ok(coverageService.getReport(id, orgId));
    }

    @PostMapping("/api/questionnaires/{id}/coverage/refresh")
    public ResponseEntity<Void> refresh(@PathVariable UUID id) {
        UUID orgId = TenantContext.get();
        coverageService.refreshAnalysis(id, orgId);
        return ResponseEntity.accepted().build();
    }
}