package io.github.badfisher.ailog.persistence.pipeline;

import java.util.List;
import java.util.UUID;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.pipeline.PipelineRepository;
import io.github.badfisher.ailog.domain.pipeline.PipelineTaskViews;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskEntity;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogAnalysisTaskEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;

/** 查询和首次 AI 准备适配器；行锁覆盖检查及插入，远程调用不进入事务。 */
@Repository
public class MybatisPlusPipelineRepository implements PipelineRepository {

    private final PipelineQueryMapper queries;
    private final AiLogAnalysisTaskMapper analyses;
    private final AiLogAiTaskMapper tasks;

    public MybatisPlusPipelineRepository(PipelineQueryMapper queries,
            AiLogAnalysisTaskMapper analyses, AiLogAiTaskMapper tasks) {
        this.queries = queries;
        this.analyses = analyses;
        this.tasks = tasks;
    }

    @Override
    public List<PipelineTaskViews.AnalysisTask> findAnalyses(long beforeId) {
        return queries.findAnalyses(beforeId);
    }

    @Override
    public List<PipelineTaskViews.AiTask> findAiTasks(long beforeId) {
        return queries.findAiTasks(beforeId);
    }

    @Override
    public List<PipelineTaskViews.AiItem> findItems(long taskId, long beforeId) {
        return queries.findItems(taskId, beforeId);
    }

    @Override
    public List<PipelineTaskViews.AiAttempt> findAttempts(long itemId, long beforeId) {
        return queries.findAttempts(itemId, beforeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long prepareInitialAiTask(long analysisTaskId, AiTaskPlan plan) {
        AiLogAnalysisTaskEntity analysis = analyses.lockById(analysisTaskId);
        if (analysis == null || !"SUCCESS".equals(analysis.getStatus())) {
            throw new IllegalArgumentException("只有成功解析的任务可以创建 AI 分析");
        }
        AiLogAiTaskEntity existing = tasks.selectOne(Wrappers.<AiLogAiTaskEntity>lambdaQuery()
                .eq(AiLogAiTaskEntity::getAnalysisTaskId, analysisTaskId)
                .eq(AiLogAiTaskEntity::getProviderCode, plan.getProviderCode())
                .eq(AiLogAiTaskEntity::getModelCode, plan.getModelCode())
                .eq(AiLogAiTaskEntity::getRunNo, 1));
        if (existing != null) {
            return existing.getId();
        }
        AiLogAiTaskEntity task = new AiLogAiTaskEntity();
        task.setTaskNo("AI-" + UUID.randomUUID());
        task.setAnalysisTaskId(analysisTaskId);
        task.setEnvironment(analysis.getEnvironment());
        task.setSystemCode(analysis.getSystemCode());
        task.setModuleCode(analysis.getModuleCode());
        task.setProviderCode(plan.getProviderCode());
        task.setModelCode(plan.getModelCode());
        task.setRunNo(1);
        task.setSelectionLimit(plan.getSelectionLimit());
        task.setStatus("PENDING");
        tasks.insert(task);
        return task.getId();
    }
}
