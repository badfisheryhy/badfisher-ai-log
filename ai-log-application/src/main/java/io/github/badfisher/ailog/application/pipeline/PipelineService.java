package io.github.badfisher.ailog.application.pipeline;

import java.util.List;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.pipeline.PipelineRepository;
import io.github.badfisher.ailog.domain.pipeline.PipelineTaskViews;

/** 流水线查询与准备用例，不依赖 HTTP、Mapper 或持久化实体。 */
public final class PipelineService {

    private final PipelineRepository repository;
    private final AiTaskPlan plan;

    public PipelineService(PipelineRepository repository, AiTaskPlan plan) {
        this.repository = repository;
        this.plan = plan;
    }

    public List<PipelineTaskViews.AnalysisTask> analyses(long beforeId) {
        return repository.findAnalyses(beforeId);
    }

    public List<PipelineTaskViews.AiTask> aiTasks(long beforeId) {
        return repository.findAiTasks(beforeId);
    }

    public List<PipelineTaskViews.AiItem> items(long taskId, long beforeId) {
        return repository.findItems(taskId, beforeId);
    }

    public List<PipelineTaskViews.AiAttempt> attempts(long itemId, long beforeId) {
        return repository.findAttempts(itemId, beforeId);
    }

    public long prepare(long analysisTaskId) {
        if (!plan.isEnabled()) {
            throw new IllegalArgumentException("请先配置并启用 AI 分析");
        }
        if (analysisTaskId <= 0) {
            throw new IllegalArgumentException("解析任务 ID 必须为正数");
        }
        return repository.prepareInitialAiTask(analysisTaskId, plan);
    }
}
