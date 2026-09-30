package io.github.badfisher.ailog.persistence.pipeline;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import io.github.badfisher.ailog.domain.pipeline.PipelineTaskViews;

/** 流水线有界游标查询；SQL 与分页限制集中在 XML。 */
@Mapper
public interface PipelineQueryMapper {

    List<PipelineTaskViews.AnalysisTask> findAnalyses(@Param("beforeId") long beforeId);

    List<PipelineTaskViews.AiTask> findAiTasks(@Param("beforeId") long beforeId);

    List<PipelineTaskViews.AiItem> findItems(@Param("taskId") long taskId,
            @Param("beforeId") long beforeId);

    List<PipelineTaskViews.AiAttempt> findAttempts(@Param("itemId") long itemId,
            @Param("beforeId") long beforeId);
}
