package com.secai.service;

import com.secai.domain.document.DocumentChunk;
import com.secai.domain.document.DocumentChunkRepository;
import com.secai.domain.questionnaire.*;
import com.secai.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnswerGenerationServiceTest {
    @Mock
    private QuestionRepository questionRepo;

    @Mock
    private AiGenerationJobRepository jobRepo;

    @Mock
    private QuestionnaireRepository questionnaireRepo;

    @Mock
    private DocumentChunkRepository chunkRepo;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private LlmService llmService;

    @Spy
    @InjectMocks
    private AnswerGenerationService service;

    private UUID orgId;
    private UUID questionnaireId;
    private UUID jobId;

    private Questionnaire questionnaire;
    private AiGenerationJob job;
    private Question question;

    @BeforeEach
    void setup() {

        orgId = UUID.randomUUID();
        questionnaireId = UUID.randomUUID();
        jobId = UUID.randomUUID();

        questionnaire = new Questionnaire();
        questionnaire.setId(questionnaireId);
        questionnaire.setOrganizationId(orgId);
        questionnaire.setStatus(QuestionnaireStatus.GENERATING);

        job = new AiGenerationJob();
        job.setId(jobId);
        job.setQuestionnaireId(questionnaireId);
        job.setStatus(AiGenerationJob.AiJobStatus.PENDING);

        question = Question.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .questionnaireId(questionnaireId)
                .questionText("Do you encrypt data?")
                .sortOrder(1)
                .status(QuestionStatus.PENDING)
                .build();
    }

    // startGeneration()
    @Test
    void shouldStartGeneration() {

        when(questionnaireRepo.findByIdAndOrganizationId(questionnaireId, orgId))
                .thenReturn(Optional.of(questionnaire));

        when(jobRepo.findByQuestionnaireId(questionnaireId))
                .thenReturn(Optional.of(job));

        when(questionRepo.countByQuestionnaireIdAndOrganizationId(questionnaireId, orgId))
                .thenReturn(5L);

        doNothing().when(service).runGenerationAsync(any(), any(), any());

        AiGenerationJob result =
                service.startGeneration(questionnaireId, orgId);

        assertEquals(AiGenerationJob.AiJobStatus.RUNNING, result.getStatus());

        verify(jobRepo).save(job);

        verify(questionnaireRepo).save(questionnaire);

        assertEquals(QuestionnaireStatus.GENERATING,
                questionnaire.getStatus());
    }

    @Test
    void shouldThrowWhenQuestionnaireMissing() {

        when(questionnaireRepo.findByIdAndOrganizationId(questionnaireId, orgId))
                .thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> service.startGeneration(questionnaireId, orgId));
    }

    @Test
    void shouldThrowWhenJobMissing() {

        when(questionnaireRepo.findByIdAndOrganizationId(questionnaireId, orgId))
                .thenReturn(Optional.of(questionnaire));

        when(jobRepo.findByQuestionnaireId(questionnaireId))
                .thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> service.startGeneration(questionnaireId, orgId));
    }

    @Test
    void shouldNotRestartRunningJob() {

        job.setStatus(AiGenerationJob.AiJobStatus.RUNNING);

        when(questionnaireRepo.findByIdAndOrganizationId(questionnaireId, orgId))
                .thenReturn(Optional.of(questionnaire));

        when(jobRepo.findByQuestionnaireId(questionnaireId))
                .thenReturn(Optional.of(job));

        AiGenerationJob result =
                service.startGeneration(questionnaireId, orgId);

        assertEquals(AiGenerationJob.AiJobStatus.RUNNING, result.getStatus());

        verify(questionRepo, never())
                .countByQuestionnaireIdAndOrganizationId(any(), any());

        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldThrowWhenNoQuestions() {

        when(questionnaireRepo.findByIdAndOrganizationId(questionnaireId, orgId))
                .thenReturn(Optional.of(questionnaire));

        when(jobRepo.findByQuestionnaireId(questionnaireId))
                .thenReturn(Optional.of(job));

        when(questionRepo.countByQuestionnaireIdAndOrganizationId(questionnaireId, orgId))
                .thenReturn(0L);

        assertThrows(IllegalStateException.class,
                () -> service.startGeneration(questionnaireId, orgId));
    }


    // generateAnswer()
    @Test
    void shouldGenerateAnswer() {

        float[] embedding = {1f,2f};

        when(embeddingService.embed(anyString()))
                .thenReturn(embedding);

        when(embeddingService.toVectorString(embedding))
                .thenReturn("[1,2]");

        DocumentChunk chunk = new DocumentChunk();

        chunk.setSectionTitle("Encryption");

        chunk.setText("AES-256 encryption");

        chunk.setDistance(0.1);

        when(chunkRepo.findSimilar(any(), any(), anyDouble(), anyInt()))
                .thenReturn(List.of(chunk));

        when(llmService.complete(anyString(), anyString()))
                .thenReturn("""
                    Answer: Yes.
                    Evidence: Encryption
                    """);

        service.generateAnswer(question, orgId);

        assertEquals("Yes.", question.getAiAnswer());

        assertEquals("Encryption",
                question.getEvidence());

        assertEquals(QuestionStatus.GENERATED,
                question.getStatus());

        verify(questionRepo).save(question);
    }

    @Test
    void shouldGenerateLowConfidenceWhenNoChunksFound() {

        when(embeddingService.embed(anyString()))
                .thenReturn(new float[]{1});

        when(embeddingService.toVectorString(any()))
                .thenReturn("[1]");

        when(chunkRepo.findSimilar(any(), any(), anyDouble(), anyInt()))
                .thenReturn(Collections.emptyList());

        when(llmService.complete(any(), any()))
                .thenReturn("""
                    Answer: Insufficient evidence.
                    Evidence: N/A
                    """);

        service.generateAnswer(question, orgId);

        assertEquals(0.05,
                question.getRetrievalScore());
    }

    @Test
    void shouldHandleMalformedLlmResponse() {

        when(embeddingService.embed(any()))
                .thenReturn(new float[]{1});

        when(embeddingService.toVectorString(any()))
                .thenReturn("[1]");

        when(chunkRepo.findSimilar(any(), any(), anyDouble(), anyInt()))
                .thenReturn(Collections.emptyList());

        when(llmService.complete(any(), any()))
                .thenReturn("YES");

        service.generateAnswer(question, orgId);

        assertEquals("YES",
                question.getAiAnswer());

        assertEquals("N/A",
                question.getEvidence());
    }

    @Test
    void shouldMarkQuestionFailed() {

        service.markQuestionFailed(question);

        assertEquals(
                "Answer generation failed for this question. Please enter manually.",
                question.getAiAnswer());

        assertEquals("N/A", question.getEvidence());

        assertEquals(0.0,
                question.getRetrievalScore());

        assertEquals(QuestionStatus.GENERATED,
                question.getStatus());

        verify(questionRepo).save(question);
    }


    // runGenerationAsync()
    @Test
    void shouldCompleteGenerationJob() {

        when(jobRepo.findById(jobId))
                .thenReturn(Optional.of(job));

        when(questionRepo.findByQuestionnaireIdAndOrganizationIdOrderBySortOrder(
                questionnaireId, orgId))
                .thenReturn(List.of(question));

//        AnswerGenerationService spy =
//                Mockito.spy(service);

        doNothing().when(service)
                .generateAnswer(any(), any());

        when(questionnaireRepo.findById(questionnaireId))
                .thenReturn(Optional.of(questionnaire));

        service.runGenerationAsync(jobId, questionnaireId, orgId);

        assertEquals(AiGenerationJob.AiJobStatus.COMPLETED,
                job.getStatus());

        assertEquals(1,
                job.getCompletedQuestions());

        assertEquals(QuestionnaireStatus.COMPLETED,
                questionnaire.getStatus());
    }

    @Test
    void shouldContinueWhenQuestionFails() {

        when(jobRepo.findById(jobId))
                .thenReturn(Optional.of(job));

        when(questionRepo.findByQuestionnaireIdAndOrganizationIdOrderBySortOrder(
                questionnaireId,
                orgId))
                .thenReturn(List.of(question));

//        AnswerGenerationService spy =
//                Mockito.spy(service);
//
        doThrow(new RuntimeException("boom"))
                .when(service)
                .generateAnswer(any(), any());
//
        doNothing()
                .when(service)
                .markQuestionFailed(any());

        when(questionnaireRepo.findById(questionnaireId))
                .thenReturn(Optional.of(questionnaire));

        service.runGenerationAsync(jobId, questionnaireId, orgId);

        assertEquals(AiGenerationJob.AiJobStatus.COMPLETED,
                job.getStatus());

        verify(service).markQuestionFailed(question);
    }

    @Test
    void shouldMarkJobFailedWhenPipelineFails() {

        when(jobRepo.findById(jobId))
                .thenReturn(Optional.of(job));

        when(questionRepo.findByQuestionnaireIdAndOrganizationIdOrderBySortOrder(
                questionnaireId,
                orgId))
                .thenThrow(new RuntimeException());

        when(questionnaireRepo.findById(questionnaireId))
                .thenReturn(Optional.of(questionnaire));

        service.runGenerationAsync(jobId,
                questionnaireId,
                orgId);

        assertEquals(AiGenerationJob.AiJobStatus.FAILED,
                job.getStatus());

        assertEquals(QuestionnaireStatus.FAILED,
                questionnaire.getStatus());
    }

}

