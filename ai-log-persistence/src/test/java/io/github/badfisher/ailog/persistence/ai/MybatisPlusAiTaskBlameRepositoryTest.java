package io.github.badfisher.ailog.persistence.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.ai.AiTaskSourceLocation;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;

class MybatisPlusAiTaskBlameRepositoryTest {
    private AiLogAiTaskItemMapper itemMapper;
    private AiLogErrorEventMapper eventMapper;
    private MybatisPlusAiTaskBlameRepository repository;

    @BeforeEach
    void setUp() {
        eventMapper = mock(AiLogErrorEventMapper.class);
        itemMapper = mock(AiLogAiTaskItemMapper.class);
        io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskItemEntity item =
                new io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskItemEntity();
        item.setId(3L);
        item.setIssueGroupId(4L);
        item.setSampleEventId(50L);
        when(itemMapper.selectById(3L)).thenReturn(item);
        repository = new MybatisPlusAiTaskBlameRepository(itemMapper, eventMapper);
    }

    @Test
    void businessLocationHasPriority() {
        AiLogErrorEventEntity event = event();
        event.setBusinessClass("com.example.BusinessService");
        event.setBusinessLine(Integer.valueOf(21));
        event.setLoggerClass("com.example.LoggerService");
        event.setLoggerLine(Integer.valueOf(30));
        when(eventMapper.selectById(50L))
                .thenReturn(event);

        AiTaskSourceLocation result = repository.findSourceLocation(3L, 4L);

        assertThat(result.getClassName()).isEqualTo("com.example.BusinessService");
        assertThat(result.getLineNumber()).isEqualTo(21);
        verify(eventMapper).selectById(50L);
    }

    @Test
    void invalidBusinessPairFallsBackToLogger() {
        AiLogErrorEventEntity event = event();
        event.setBusinessLine(Integer.valueOf(21));
        event.setLoggerClass("com.example.LoggerService");
        event.setLoggerLine(Integer.valueOf(30));
        when(eventMapper.selectById(50L))
                .thenReturn(event);

        AiTaskSourceLocation result = repository.findSourceLocation(3L, 4L);

        assertThat(result.getClassName()).isEqualTo("com.example.LoggerService");
        assertThat(result.getLineNumber()).isEqualTo(30);
    }

    @Test
    void noValidPairReturnsNull() {
        AiLogErrorEventEntity event = event();
        event.setBusinessClass("com.example.BusinessService");
        event.setLoggerLine(Integer.valueOf(30));
        when(eventMapper.selectById(50L))
                .thenReturn(event);

        assertThat(repository.findSourceLocation(3L, 4L)).isNull();
    }

    private static AiLogErrorEventEntity event() {
        AiLogErrorEventEntity event = new AiLogErrorEventEntity();
        event.setIssueGroupId(4L);
        event.setEnvironment("dev");
        event.setSystemCode("system");
        event.setModuleCode("module");
        return event;
    }
}
