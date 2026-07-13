package com.secai.service;

import com.secai.config.TenantContext;
import com.secai.domain.document.DocumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIsolationTest {

    @Mock DocumentRepository documentRepository;
    @Mock StorageService storageService;
    @InjectMocks DocumentService documentService;

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void getById_wrongOrg_throwsNotFound() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        // Simulate: TenantContext is orgA, but findByIdAndOrganizationId with orgA returns empty
        TenantContext.set(orgA);
        when(documentRepository.findByIdAndOrganizationId(docId, orgA))
                .thenReturn(Optional.empty());  // orgB's doc not visible to orgA

        assertThatThrownBy(() -> documentService.getById(docId))
                .hasMessageContaining("not found");
    }
}