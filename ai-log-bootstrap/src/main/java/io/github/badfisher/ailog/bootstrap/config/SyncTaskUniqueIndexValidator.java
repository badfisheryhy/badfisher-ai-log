package io.github.badfisher.ailog.bootstrap.config;

import org.springframework.beans.factory.InitializingBean;

import io.github.badfisher.ailog.persistence.sync.mapper.AiLogSyncTaskMapper;

/** 日志同步链路启动前校验业务日期唯一索引，避免应用检查被并发创建绕过。 */
public class SyncTaskUniqueIndexValidator implements InitializingBean {

    /** V2.9.0 约定的唯一索引名称。 */
    static final String REQUIRED_INDEX = "uk_sync_scope";

    /** 数据库访问模板。 */
    private final AiLogSyncTaskMapper syncTaskMapper;

    /**
     * 创建唯一索引校验器。
     *
     * @param mapper 同步任务 Mapper
     */
    public SyncTaskUniqueIndexValidator(AiLogSyncTaskMapper mapper) {
        syncTaskMapper = mapper;
    }

    /** 动态同步 Bean 对外可用前执行只读校验。 */
    @Override
    public void afterPropertiesSet() {
        String columns = syncTaskMapper.selectUniqueIndexColumns(REQUIRED_INDEX);
        if (!"environment:FULL,system_code:FULL,log_date:FULL".equals(columns)) {
            throw new IllegalStateException("日志同步链路启动失败：数据库缺少V2.9.0唯一索引"
                    + REQUIRED_INDEX + "，请按sql/schema.sql核对数据库结构");
        }
    }
}
