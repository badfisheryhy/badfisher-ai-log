package io.github.badfisher.ailog.domain.sync;

import lombok.Getter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步任务聚合持久化端口；同步域只通过该端口维护任务事实。
 * <p>
 * 任务、模块、文件三级状态机由应用服务驱动，仓储仅负责原子地落库与回读。
 */
public interface LogSyncTaskRepository {

    /**
     * 创建同步任务及其模块、文件明细。
     *
     * @param taskNo      任务编号，全局唯一
     * @param environment 环境编码
     * @param systemCode  系统编码
     * @param logDate     日志日期
     * @param triggerType 触发来源类型编码
     * @param modules     模块明细列表
     * @return 任务注册结果，含任务 ID 与各模块任务 ID
     */
    TaskRegistration createTask(String taskNo, String environment, String systemCode,
            LocalDate logDate, String triggerType, List<ModuleSpec> modules);

    /**
     * 加载重试所需的原任务聚合。
     *
     * <p>聚合中的模块任务映射<b>只包含 FAILED 或 CONFLICT 状态的模块</b>：已成功模块的
     * ready 文件与分析结果保持原样；PENDING、RUNNING 等非终态会被拒绝。</p>
     *
     * @param taskId 任务 ID
     * @return 重试任务聚合
     */
    RetryTask loadRetryTask(long taskId);

    /**
     * 汇总任务下<b>全部</b>模块任务的当前结局，供重试后按全量口径回填父任务统计。
     *
     * @param taskId 任务 ID
     * @return 模块结局汇总
     */
    ModuleOutcomeSummary summarizeModuleOutcomes(long taskId);

    /** 将允许重试的父任务与预期数量的失败/冲突模块条件重置为 PENDING。 */
    void resetForRetry(long taskId, int expectedModuleCount);

    /** 将任务标记为执行中。 */
    void markTaskRunning(long taskId);

    /** 将模块任务标记为执行中。 */
    void markModuleRunning(long moduleTaskId);

    /** 记录单文件开始同步。 */
    void markFileSyncing(long moduleTaskId, LogChannel channel);

    /**
     * 记录单文件同步完成。
     *
     * @param moduleTaskId 模块任务 ID
     * @param channel      日志通道
     * @param remotePath   远程文件路径
     * @param localPath    本地文件路径
     * @param fileName     文件名
     * @param fileSize     文件大小（字节）
     */
    void markFileReady(long moduleTaskId, LogChannel channel, String remotePath, String localPath,
            String fileName, long fileSize);

    /** 记录单文件同步失败及失败原因。 */
    void markFileFailed(long moduleTaskId, LogChannel channel, String errorMessage);

    /** 记录模块同步成功及同步文件数。 */
    void markModuleSuccess(long moduleTaskId, int totalFileCount);

    /** 记录模块同步失败及失败原因。 */
    void markModuleFailed(long moduleTaskId, int totalFileCount, String errorMessage);

    /** 记录模块因其他实例已持锁而跳过，不计为同步失败。 */
    void markModuleConflict(long moduleTaskId, String message);

    /**
     * 终结任务并写入汇总结果。
     *
     * @param taskId       任务 ID
     * @param status       任务终态
     * @param successCount 成功模块数
     * @param failedCount  失败模块数
     * @param errorMessage 失败原因；无失败时为 {@code null}
     */
    void completeTask(long taskId, SyncTaskStatus status, int successCount, int failedCount,
            String errorMessage);

    /** 任务中单个模块的持久化明细。 */
    @Getter
    final class ModuleSpec {
        /** 模块配置主键 ID。 */
        private final Long moduleConfigId;

        /** 模块编码。 */
        private final String moduleCode;

        /** 该模块下待同步文件明细。 */
        private final List<FileSpec> files;

        /**
         * 构造模块明细。
         *
         * @param moduleConfigId 模块配置主键 ID
         * @param moduleCode     模块编码
         * @param files          文件明细列表，内部做防御性拷贝
         */
        public ModuleSpec(Long moduleConfigId, String moduleCode, List<FileSpec> files) {
            this.moduleConfigId = moduleConfigId;
            this.moduleCode = moduleCode;
            this.files = Collections.unmodifiableList(new ArrayList<FileSpec>(files));
        }

    }

    /** 单个待同步文件的持久化明细。 */
    @Getter
    final class FileSpec {
        /** 日志通道。 */
        private final LogChannel channel;

        /** 远程完整路径。 */
        private final String remotePath;

        /** 本地存放路径。 */
        private final String localPath;

        /** 文件名。 */
        private final String fileName;

        /**
         * 构造文件明细。
         *
         * @param channel    日志通道
         * @param remotePath 远程完整路径
         * @param localPath  本地存放路径
         * @param fileName   文件名
         */
        public FileSpec(LogChannel channel, String remotePath, String localPath, String fileName) {
            this.channel = channel;
            this.remotePath = remotePath;
            this.localPath = localPath;
            this.fileName = fileName;
        }

    }

    /** 任务创建结果，携带任务 ID 与各模块任务 ID 映射。 */
    final class TaskRegistration {
        /** 任务 ID。
         * -- GETTER --
         *  获取任务 ID。
         *
         */
        @Getter
        private final long taskId;

        /** 模块编码到模块任务 ID 的映射。 */
        private final Map<String, Long> moduleTaskIds;

        /**
         * 构造注册结果。
         *
         * @param taskId        任务 ID
         * @param moduleTaskIds 模块编码到模块任务 ID 的映射
         */
        public TaskRegistration(long taskId, Map<String, Long> moduleTaskIds) {
            this.taskId = taskId;
            this.moduleTaskIds = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Long>(moduleTaskIds));
        }

        /**
         * 获取指定模块的任务 ID，未注册时抛出异常。
         *
         * @param moduleCode 模块编码
         * @return 模块任务 ID
         */
        public long requiredModuleTaskId(String moduleCode) {
            Long value = moduleTaskIds.get(moduleCode);
            if (value == null) {
                throw new IllegalStateException("Module task not registered: " + moduleCode);
            }
            return value;
        }
    }

    /** 重试任务聚合，保存原任务上下文供重跑使用；模块映射只含失败或冲突模块。 */
    @Getter
    final class RetryTask {
        /** 任务 ID。 */
        private final long taskId;

        /** 环境编码。 */
        private final String environment;

        /** 系统编码。 */
        private final String systemCode;

        /** 日志日期。 */
        private final LocalDate logDate;

        /** 原任务状态。 */
        private final String status;

        /** 模块编码到模块任务 ID 的映射。 */
        private final Map<String, Long> moduleTaskIds;

        /**
         * 构造重试任务聚合。
         *
         * @param taskId        任务 ID
         * @param environment   环境编码
         * @param systemCode    系统编码
         * @param logDate       日志日期
         * @param status        原任务状态
         * @param moduleTaskIds 模块编码到模块任务 ID 的映射
         */
        public RetryTask(long taskId, String environment, String systemCode,
                LocalDate logDate, String status, Map<String, Long> moduleTaskIds) {
            this.taskId = taskId;
            this.environment = environment;
            this.systemCode = systemCode;
            this.logDate = logDate;
            this.status = status;
            this.moduleTaskIds = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Long>(moduleTaskIds));
        }

    }

    /** 任务下全部模块任务的结局汇总，成功口径 = 模块状态为 SUCCESS。 */
    @Getter
    final class ModuleOutcomeSummary {
        /** 模块总数。 */
        private final int totalModules;
        /** 成功模块数（状态 SUCCESS）。 */
        private final int successModules;
        /** 失败或未完成模块数（不含锁冲突）。 */
        private final int failedModules;
        /** 锁冲突跳过模块数。 */
        private final int conflictModules;

        /**
         * 构造结局汇总。
         *
         * @param total   模块总数
         * @param success 成功模块数
         */
        public ModuleOutcomeSummary(int total, int success) {
            this(total, success, 0);
        }

        /**
         * 构造包含锁冲突的结局汇总。
         *
         * @param total    模块总数
         * @param success  成功模块数
         * @param conflict 锁冲突模块数
         */
        public ModuleOutcomeSummary(int total, int success, int conflict) {
            totalModules = total;
            successModules = success;
            conflictModules = conflict;
            failedModules = total - success - conflict;
        }

    }
}
