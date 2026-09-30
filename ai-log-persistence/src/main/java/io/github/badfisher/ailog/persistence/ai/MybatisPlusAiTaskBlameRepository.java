package io.github.badfisher.ailog.persistence.ai;

import java.time.LocalDateTime;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskItemEntity;

import io.github.badfisher.ailog.domain.ai.AiTaskBlameRepository;
import io.github.badfisher.ailog.domain.ai.AiTaskClaim;
import io.github.badfisher.ailog.domain.ai.AiTaskSourceLocation;
import io.github.badfisher.ailog.domain.ai.GitBlameResult;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;

/** 基于持久化 Event 选择源码位置并以领取令牌保护 Author 写入。 */
public final class MybatisPlusAiTaskBlameRepository implements AiTaskBlameRepository {
    private final AiLogAiTaskItemMapper itemMapper;
    private final AiLogErrorEventMapper eventMapper;

    public MybatisPlusAiTaskBlameRepository(AiLogAiTaskItemMapper items,
            AiLogErrorEventMapper events) {
        itemMapper = items;
        eventMapper = events;
    }

    @Override
    public AiTaskSourceLocation findSourceLocation(long itemId, long issueGroupId) {
        AiLogAiTaskItemEntity item = itemMapper.selectById(Long.valueOf(itemId));
        if (item == null || !Long.valueOf(issueGroupId).equals(item.getIssueGroupId())
                || item.getSampleEventId() == null) {
            return null;
        }
        AiLogErrorEventEntity event = eventMapper.selectById(item.getSampleEventId());
        if (event == null || !Long.valueOf(issueGroupId).equals(event.getIssueGroupId())) {
            return null;
        }
        if (valid(event.getBusinessClass(), event.getBusinessLine())) {
            return location(event, event.getBusinessClass(), event.getBusinessLine());
        }
        if (valid(event.getLoggerClass(), event.getLoggerLine())) {
            return location(event, event.getLoggerClass(), event.getLoggerLine());
        }
        return null;
    }

    @Override
    public boolean updateBlame(AiTaskClaim claim, Long operationId,
            String operationExecutionToken, GitBlameResult result, LocalDateTime now) {
        return itemMapper.updateBlame(Long.valueOf(claim.getItemId()), claim.getClaimToken(),
                operationId, operationExecutionToken, result, now) == 1;
    }

    private static boolean valid(String className, Integer lineNumber) {
        return className != null && !className.trim().isEmpty()
                && lineNumber != null && lineNumber.intValue() > 0;
    }

    private static AiTaskSourceLocation location(AiLogErrorEventEntity event,
            String className, Integer lineNumber) {
        return new AiTaskSourceLocation(event.getEnvironment(), event.getSystemCode(),
                event.getModuleCode(), className.trim(), lineNumber.intValue());
    }
}
