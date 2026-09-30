package io.github.badfisher.ailog.application.pipeline;

import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.pipeline.PipelineRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PipelineServiceTest {

    @Test
    void disabledProviderAndInvalidIdNeverPrepareTask() {
        PipelineRepository repository = mock(PipelineRepository.class);
        assertThatThrownBy(() -> new PipelineService(repository, AiTaskPlan.disabled()).prepare(1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PipelineService(repository, plan()).prepare(0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void preparesUsingConfiguredPlanAndKeepsQueryScopes() {
        PipelineRepository repository = mock(PipelineRepository.class);
        AiTaskPlan plan = plan();
        PipelineService service = new PipelineService(repository, plan);
        when(repository.prepareInitialAiTask(3, plan)).thenReturn(7L);
        assertThat(service.prepare(3)).isEqualTo(7L);
        service.analyses(12);
        service.aiTasks(11);
        service.items(7, 10);
        service.attempts(9, 8);
        verify(repository).findAnalyses(12);
        verify(repository).findAiTasks(11);
        verify(repository).findItems(7, 10);
        verify(repository).findAttempts(9, 8);
    }

    private static AiTaskPlan plan() {
        return new AiTaskPlan(true, "openai", "default", "model", "prompt", "sanitizer", "{}", 10, 1, 0);
    }
}
