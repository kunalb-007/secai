package com.secai.service;

import com.secai.config.TenantContext;
import com.secai.domain.library.ApprovedAnswer;
import com.secai.domain.library.ApprovedAnswerRepository;
import com.secai.domain.questionnaire.Question;
import com.secai.domain.questionnaire.QuestionRepository;
import com.secai.domain.questionnaire.QuestionStatus;
import com.secai.dto.library.ApprovedAnswerMatch;
import com.secai.exception.ForbiddenException;
import com.secai.exception.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnswerLibraryServiceTest {

    @Mock
    private ApprovedAnswerRepository libraryRepo;

    @Mock
    private QuestionRepository questionRepo;

    @Mock
    private EmbeddingService embeddingService;

    @InjectMocks
    private AnswerLibraryService service;

    private UUID orgId;
    private UUID questionId;
    private UUID libraryId;

    private Question question;
    private ApprovedAnswer approvedAnswer;

    @BeforeEach
    void setup() {

        orgId = UUID.randomUUID();
        questionId = UUID.randomUUID();
        libraryId = UUID.randomUUID();

        TenantContext.set(orgId);

        question = Question.builder()
                .id(questionId)
                .organizationId(orgId)
                .questionText("Do you encrypt data?")
                .aiAnswer("Yes")
                .manualAnswer(null)
                .evidence("Security Policy")
                .status(QuestionStatus.GENERATED)
                .build();

        approvedAnswer = ApprovedAnswer.builder()
                .id(libraryId)
                .organizationId(orgId)
                .sourceQuestionId(questionId)
                .sourceQuestionText(question.getQuestionText())
                .answerText("Yes")
                .evidence("Security Policy")
                .approvedByEmail("reviewer@test.com")
                .build();
    }

    // indexApprovedAnswer()
    @Test
    void shouldCreateNewLibraryEntry() {

        float[] embedding = {1f, 2f};

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.of(question));

        when(embeddingService.embed(anyString()))
                .thenReturn(embedding);

        when(embeddingService.toVectorString(any()))
                .thenReturn("[1,2]");

        when(libraryRepo.findBySourceQuestionId(questionId))
                .thenReturn(Optional.empty());

        service.indexApprovedAnswer(questionId,
                "reviewer@test.com",
                orgId);

        verify(libraryRepo).save(any(ApprovedAnswer.class));
    }

    @Test
    void shouldUpdateExistingEntry() {

        float[] embedding = {1};

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.of(question));

        when(embeddingService.embed(anyString()))
                .thenReturn(embedding);

        when(embeddingService.toVectorString(any()))
                .thenReturn("[1]");

        when(libraryRepo.findBySourceQuestionId(questionId))
                .thenReturn(Optional.of(approvedAnswer));

        service.indexApprovedAnswer(questionId,
                "reviewer@test.com",
                orgId);

        assertEquals("Yes", approvedAnswer.getAnswerText());

        verify(libraryRepo).save(approvedAnswer);
    }

    @Test
    void shouldSkipWhenQuestionMissing() {

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.empty());

        service.indexApprovedAnswer(questionId,
                "reviewer@test.com",
                orgId);

        verifyNoInteractions(embeddingService);

        verify(libraryRepo, never()).save(any());
    }

    @Test
    void shouldSkipWhenAnswerBlank() {

        question.setAiAnswer(null);
        question.setManualAnswer("");

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.of(question));

        service.indexApprovedAnswer(questionId,
                "reviewer@test.com",
                orgId);

        verifyNoInteractions(embeddingService);

        verify(libraryRepo, never()).save(any());
    }

    @Test
    void shouldIgnoreEmbeddingFailure() {

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.of(question));

        when(embeddingService.embed(anyString()))
                .thenThrow(new RuntimeException());

        assertDoesNotThrow(() ->
                service.indexApprovedAnswer(questionId,
                        "reviewer@test.com",
                        orgId));

        verify(libraryRepo, never()).save(any());
    }

    //findMatch()

    @Test
    void shouldReturnLibraryMatch() {

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.of(question));

        when(libraryRepo.countByOrganizationId(orgId))
                .thenReturn(1L);

        when(embeddingService.embed(any()))
                .thenReturn(new float[]{1});

        when(embeddingService.toVectorString(any()))
                .thenReturn("[1]");

        Object[] row = {
                libraryId,
                orgId,
                questionId,
                "Encrypt?",
                "Yes",
                "Security Policy",
                "reviewer@test.com",
                java.sql.Timestamp.from(java.time.Instant.now()),
                0.05
        };

        when(libraryRepo.findSimilarRaw(any(), any(), anyDouble(), anyInt()))
                .thenReturn(Collections.singletonList(row));

        Optional<ApprovedAnswerMatch> result =
                service.findMatch(questionId);

        assertTrue(result.isPresent());

        assertEquals("Yes",
                result.get().answerText());
    }

    @Test
    void shouldReturnEmptyForApprovedQuestion() {

        question.setStatus(QuestionStatus.APPROVED);

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.of(question));

        Optional<ApprovedAnswerMatch> result =
                service.findMatch(questionId);

        assertTrue(result.isEmpty());

        verifyNoInteractions(embeddingService);
    }

    @Test
    void shouldReturnEmptyWhenLibraryHasNoEntries() {

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.of(question));

        when(libraryRepo.countByOrganizationId(orgId))
                .thenReturn(0L);

        Optional<ApprovedAnswerMatch> result =
                service.findMatch(questionId);

        assertTrue(result.isEmpty());

        verifyNoInteractions(embeddingService);
    }

    @Test
    void shouldThrowWhenQuestionMissing() {

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> service.findMatch(questionId));
    }

    // reuseAnswer()

    @Test
    void shouldReuseLibraryAnswer() {

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.of(question));

        when(libraryRepo.findById(libraryId))
                .thenReturn(Optional.of(approvedAnswer));

        service.reuseAnswer(questionId,
                libraryId);

        assertEquals("Yes",
                question.getManualAnswer());

        assertEquals(QuestionStatus.APPROVED,
                question.getStatus());

        verify(questionRepo).save(question);
    }

    @Test
    void shouldThrowWhenQuestionBelongsToDifferentOrg() {

        question.setOrganizationId(UUID.randomUUID());

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.of(question));

        assertThrows(ForbiddenException.class,
                () -> service.reuseAnswer(questionId,
                        libraryId));
    }

    @Test
    void shouldThrowWhenLibraryEntryBelongsToDifferentOrg() {

        approvedAnswer.setOrganizationId(UUID.randomUUID());

        when(questionRepo.findById(questionId))
                .thenReturn(Optional.of(question));

        when(libraryRepo.findById(libraryId))
                .thenReturn(Optional.of(approvedAnswer));

        assertThrows(ForbiddenException.class,
                () -> service.reuseAnswer(questionId,
                        libraryId));
    }

    @Test
    void shouldReturnLibrarySize() {

        when(libraryRepo.countByOrganizationId(orgId))
                .thenReturn(27L);

        long size = service.getLibrarySize();

        assertEquals(27L, size);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }
}
