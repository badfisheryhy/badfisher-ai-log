package io.github.badfisher.ailog.bootstrap.service;

import io.github.badfisher.ailog.application.pipeline.PipelineService;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import org.springframework.stereotype.Service;

/** 将准备用例的参数拒绝转换为既有 HTTP 业务错误契约。 */
@Service
public class AiPreparationService {

    private final PipelineService pipeline;

    public AiPreparationService(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    public long prepare(long analysisTaskId) {
        try {
            return pipeline.prepare(analysisTaskId);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(exception.getMessage(), exception);
        }
    }
}
