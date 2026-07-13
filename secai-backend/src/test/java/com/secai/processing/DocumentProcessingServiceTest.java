package com.secai.processing;

import com.secai.domain.document.*;
import com.secai.processing.extractor.TextExtractor;
import com.secai.processing.extractor.TextExtractorFactory;
import com.secai.service.EmbeddingService;
import com.secai.service.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentProcessingServiceTest {

    @Mock DocumentRepository      documentRepo;
    @Mock DocumentChunkRepository chunkRepo;
    @Mock
    StorageService storageService;

    @Mock
    TextExtractorFactory extractorFactory;

    @Mock
    TextExtractor extractor;

    @Mock
    TextCleaner textCleaner;

    @Mock
    TextChunker textChunker;

    @Mock
    EmbeddingService embeddingService;

    @InjectMocks
    DocumentProcessingService processingService;

    @Test
    void process_happyPath_savesChunksAndSetsReady() throws Exception {
        // Arrange
        UUID docId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        Document doc = Document.builder()
                .id(docId)
                .organizationId(orgId)
                .filename("security-policy.pdf")
                .contentType("application/pdf")
                .status(DocumentStatus.PENDING)
                .storagePath(orgId + "/raw/" + docId + "/security-policy.pdf")
                .build();

        String sampleText = "# Access Control\n\nMFA is required for all users.\n\n"
                + "# Encryption\n\nData is encrypted using AES-256.";

        when(documentRepo.findById(docId)).thenReturn(Optional.of(doc));
        when(storageService.downloadToTemp(any(), any()))
                .thenReturn(Path.of("/tmp/test.pdf"));
        when(extractorFactory.forContentType(any(), any())).thenReturn(extractor);
        when(extractor.extract(any())).thenReturn(sampleText);
        when(storageService.uploadText(any(), any(), any())).thenReturn("some/path");

        when(textCleaner.clean(sampleText))
                .thenReturn(sampleText);

        List<TextChunker.Chunk> chunks = List.of(
                new TextChunker.Chunk(
                        "Access Control",
                        "MFA is required for all users.",
                        0,
                        8
                ),
                new TextChunker.Chunk(
                        "Encryption",
                        "Data is encrypted using AES-256.",
                        1,
                        9
                )
        );

        when(textChunker.chunk(sampleText))
                .thenReturn(chunks);

        when(embeddingService.embedAll(any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[1536]).toList();
        });
        when(documentRepo.save(any())).thenReturn(doc);

        // Act
        processingService.process(docId);

        // Assert
        verify(chunkRepo).deleteByDocumentIdAndOrganizationId(docId, orgId);
        verify(chunkRepo, atLeastOnce()).saveAll(any());

        // Verify document was saved with READY status
        ArgumentCaptor<Document> savedDoc = ArgumentCaptor.forClass(Document.class);
        verify(documentRepo, times(2)).save(savedDoc.capture()); // PROCESSING, then READY

        List<Document> savedDocs = savedDoc.getAllValues();
        assertThat(savedDocs.getLast().getStatus())
                .isEqualTo(DocumentStatus.READY);
    }

    @Test
    void process_extractionFails_throws() throws Exception {
        UUID docId = UUID.randomUUID();
        Document doc = Document.builder()
                .id(docId)
                .organizationId(UUID.randomUUID())
                .filename("bad.pdf")
                .contentType("application/pdf")
                .status(DocumentStatus.PENDING)
                .storagePath("org/raw/doc/bad.pdf")
                .build();

        when(documentRepo.findById(docId)).thenReturn(Optional.of(doc));
        when(storageService.downloadToTemp(any(), any()))
                .thenReturn(Path.of("/tmp/bad.pdf"));
        when(extractorFactory.forContentType(any(), any())).thenReturn(extractor);
        when(extractor.extract(any())).thenThrow(
                new com.secai.processing.extractor.TextExtractionException("Marker failed")
        );
        when(documentRepo.save(any())).thenReturn(doc);

        // ProcessingService re-throws; worker handles FAILED status
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> processingService.process(docId));
    }
}