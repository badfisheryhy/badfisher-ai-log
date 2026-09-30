package io.github.badfisher.ailog.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import io.github.badfisher.ailog.domain.ai.AiTaskBlameRepository;
import io.github.badfisher.ailog.domain.ai.AiTaskClaim;
import io.github.badfisher.ailog.domain.ai.AiTaskSourceLocation;
import io.github.badfisher.ailog.domain.ai.GitBlameResult;
import io.github.badfisher.ailog.domain.ai.GitBlameTool;

class AiTaskBlameEnrichmentServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-11T01:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private AiTaskBlameRepository repository;
    private GitBlameTool gitBlameTool;
    private AiTaskBlameEnrichmentService service;
    private AiTaskClaim claim;

    @BeforeEach
    void setUp() {
        repository = mock(AiTaskBlameRepository.class);
        gitBlameTool = mock(GitBlameTool.class);
        service = new AiTaskBlameEnrichmentService(repository, gitBlameTool, CLOCK);
        claim = new AiTaskClaim(1L, 2L, 4L,
                "provider", "model", "prompt", "sanitizer", 0, 3, "claim");
    }

    @Test
    void resolvesAndWritesBlameBeforeReturning() {
        AiTaskSourceLocation location = location();
        GitBlameResult result = new GitBlameResult("author",
                LocalDateTime.of(2026, 9, 10, 12, 30));
        when(repository.findSourceLocation(1L, 4L)).thenReturn(location);
        when(gitBlameTool.tryBlame(location)).thenReturn(Optional.of(result));
        when(repository.updateBlame(claim, null, null, result,
                LocalDateTime.now(CLOCK))).thenReturn(true);

        service.enrich(claim, null, null);

        InOrder order = inOrder(repository, gitBlameTool);
        order.verify(repository).findSourceLocation(1L, 4L);
        order.verify(gitBlameTool).tryBlame(location);
        order.verify(repository).updateBlame(claim, null, null, result,
                LocalDateTime.now(CLOCK));
    }

    @Test
    void noSourceLocationDoesNotInvokeGit() {
        service.enrich(claim, null, null);

        verify(gitBlameTool, never()).tryBlame(any());
        verify(repository, never()).updateBlame(any(), any(), any(), any(), any());
    }

    @Test
    void gitFailureDoesNotEscape() {
        AiTaskSourceLocation location = location();
        when(repository.findSourceLocation(1L, 4L)).thenReturn(location);
        when(gitBlameTool.tryBlame(location)).thenThrow(new IllegalStateException("git failed"));

        service.enrich(claim, 9L, "execution");

        verify(repository, never()).updateBlame(any(), any(), any(), any(), any());
    }

    @Test
    void tryResolveIsolatesRepositoryFailure() {
        when(repository.findSourceLocation(1L, 4L))
                .thenThrow(new IllegalStateException("database failed"));

        assertThat(service.tryResolve(1L, 4L)).isEmpty();
    }

    private static AiTaskSourceLocation location() {
        return new AiTaskSourceLocation("dev", "system", "module",
                "com.example.OrderService", 42);
    }
}
