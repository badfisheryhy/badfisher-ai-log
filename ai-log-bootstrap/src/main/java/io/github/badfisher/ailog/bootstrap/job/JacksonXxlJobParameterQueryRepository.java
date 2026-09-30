package io.github.badfisher.ailog.bootstrap.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

/** 基于 Jackson 的 XXL-JOB 参数查询仓储。 */
@Repository
public class JacksonXxlJobParameterQueryRepository
        implements XxlJobParameterQueryRepository {

    private final ObjectMapper objectMapper;

    public JacksonXxlJobParameterQueryRepository(ObjectMapper mapper) {
        objectMapper = mapper;
    }

    @Override
    public XxlLogSyncJobParameter queryLogSyncParameter(String rawParameter) {
        return read(rawParameter, XxlLogSyncJobParameter.class, "log sync");
    }

    @Override
    public XxlErrorAnalysisJobParameter queryErrorAnalysisParameter(String rawParameter) {
        return read(rawParameter, XxlErrorAnalysisJobParameter.class, "error analysis");
    }

    @Override
    public XxlFileCleanupJobParameter queryFileCleanupParameter(String rawParameter) {
        if (rawParameter == null || rawParameter.trim().isEmpty()) {
            return new XxlFileCleanupJobParameter();
        }
        return read(rawParameter, XxlFileCleanupJobParameter.class, "file cleanup");
    }

    @Override
    public XxlDailyLogAnalysisJobParameter queryDailyLogAnalysisParameter(String rawParameter) {
        return read(rawParameter, XxlDailyLogAnalysisJobParameter.class,
                "daily log analysis");
    }

    @Override
    public XxlAiDailyReportJobParameter queryAiDailyReportParameter(String rawParameter) {
        if (rawParameter == null || rawParameter.trim().isEmpty()) {
            throw new IllegalArgumentException("日报参数不能为空，至少提供 environment");
        }
        try {
            XxlAiDailyReportJobParameter parameter = objectMapper.readerFor(XxlAiDailyReportJobParameter.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(rawParameter);
            if (parameter == null) {
                throw new IllegalArgumentException("日报参数必须是 JSON 对象");
            }
            return parameter;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("日报参数格式错误：仅支持 environment/systemCode/logDate/resend");
        }
    }

    private <T> T read(String rawParameter, Class<T> parameterType, String parameterName) {
        try {
            return objectMapper.readValue(rawParameter, parameterType);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid XXL-JOB " + parameterName + " parameter", ex);
        }
    }
}
