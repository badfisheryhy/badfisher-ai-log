package io.github.badfisher.ailog.domain.pipeline;

import java.util.List;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;

/** 流水线查询及首次 AI 准备的持久化边界。 */
public interface PipelineRepository {

    List<PipelineTaskViews.AnalysisTask> findAnalyses(long beforeId);

    List<PipelineTaskViews.AiTask> findAiTasks(long beforeId);

    List<PipelineTaskViews.AiItem> findItems(long taskId, long beforeId);

    List<PipelineTaskViews.AiAttempt> findAttempts(long itemId, long beforeId);

    /** 在同一事务内锁定解析任务并幂等创建首次 AI 批次。 */
    long prepareInitialAiTask(long analysisTaskId, AiTaskPlan plan);
}
