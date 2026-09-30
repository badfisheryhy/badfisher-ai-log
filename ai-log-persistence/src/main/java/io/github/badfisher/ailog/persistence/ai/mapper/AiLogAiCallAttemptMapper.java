package io.github.badfisher.ailog.persistence.ai.mapper;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.domain.ai.AiAnalysisResult;
import io.github.badfisher.ailog.domain.ai.AiCallFailure;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiCallAttemptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** AI 真实调用 Attempt Mapper。 */
@Mapper
public interface AiLogAiCallAttemptMapper extends BaseMapper<AiLogAiCallAttemptEntity> {
    int finishSuccess(@Param("attemptId") Long attemptId,
            @Param("result") AiAnalysisResult result,
            @Param("latencyMillis") long latencyMillis,
            @Param("now") LocalDateTime now);
    int finishFailure(@Param("attemptId") Long attemptId,
            @Param("failure") AiCallFailure failure,
            @Param("latencyMillis") long latencyMillis, @Param("now") LocalDateTime now);
    int failExpiredRunningAttempts(@Param("now") LocalDateTime now);

    /** 收口指定操作中 Item 租约已过期的运行中 Attempt。 */
    int failExpiredRerunAttempts(@Param("operationId") Long operationId,
            @Param("now") LocalDateTime now);
}
