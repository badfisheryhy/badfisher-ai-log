package io.github.badfisher.ailog.application.ai;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import io.github.badfisher.ailog.domain.ai.AiTaskBlameRepository;
import io.github.badfisher.ailog.domain.ai.AiTaskClaim;
import io.github.badfisher.ailog.domain.ai.AiTaskSourceLocation;
import io.github.badfisher.ailog.domain.ai.GitBlameResult;
import io.github.badfisher.ailog.domain.ai.GitBlameTool;

/** 在 AI 调用前以 best-effort 方式补充源码最后修改 Author 信息。 */
@Slf4j
public final class AiTaskBlameEnrichmentService {
    private final AiTaskBlameRepository repository;
    private final GitBlameTool gitBlameTool;
    private final Clock clock;

    public AiTaskBlameEnrichmentService(AiTaskBlameRepository blameRepository,
            GitBlameTool blameTool) {
        this(blameRepository, blameTool, Clock.systemDefaultZone());
    }

    AiTaskBlameEnrichmentService(AiTaskBlameRepository blameRepository,
            GitBlameTool blameTool, Clock serviceClock) {
        repository = blameRepository;
        gitBlameTool = blameTool;
        clock = serviceClock;
    }

    /** 使用本次分析的代表 Event 解析 Author，不修改 AI Item。 */
    public Optional<GitBlameResult> tryResolve(long itemId, long issueGroupId) {
        try {
            AiTaskSourceLocation location = repository.findSourceLocation(
                    itemId, issueGroupId);
            if (location == null) {
                return Optional.empty();
            }
            return gitBlameTool.tryBlame(location);
        } catch (RuntimeException ex) {
            log.warn("event=ai_item_blame_resolve_failed AI Item Author解析失败："
                            + "itemId={}, issueGroupId={}",
                    itemId, issueGroupId, ex);
            return Optional.empty();
        }
    }

    /** 查询并写入本次领取的归属；任何增强失败均不影响后续 AI 调用。 */
    public void enrich(AiTaskClaim claim, Long operationId, String operationExecutionToken) {
        try {
            Optional<GitBlameResult> result = tryResolve(
                    claim.getItemId(), claim.getIssueGroupId());
            if (!result.isPresent()) {
                return;
            }
            if (!repository.updateBlame(claim, operationId,
                    operationExecutionToken, result.get(), LocalDateTime.now(clock))) {
                log.warn("event=ai_item_blame_update_skipped AI Item Author写入未命中有效领取："
                                + "operationId={}, aiTaskId={}, itemId={}",
                        operationId, claim.getAiTaskId(), claim.getItemId());
            }
        } catch (RuntimeException ex) {
            log.warn("event=ai_item_blame_enrichment_failed AI Item Author增强失败，继续AI调用："
                            + "operationId={}, aiTaskId={}, itemId={}",
                    operationId, claim.getAiTaskId(), claim.getItemId(), ex);
        }
    }
}
