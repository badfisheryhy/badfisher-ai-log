package io.github.badfisher.ailog.application.sync;

import lombok.Getter;

/** 批量任务中的单模块结果，失败不会阻断其他模块。 */
@Getter
public final class BatchModuleSyncResult {

    /** 模块编码。
     * -- GETTER --
     *  返回模块编码。
     */
    private final String moduleCode;
    /** 是否同步成功。
     * -- GETTER --
     *  返回是否同步成功。
     */
    private final boolean success;
    /** 是否因已有同步持锁而跳过。
     * -- GETTER --
     *  返回是否因锁冲突而跳过。
     */
    private final boolean conflict;
    /** 失败错误码，成功时为空。
     * -- GETTER --
     *  返回失败错误码。
     */
    private final String errorCode;
    /** 结果描述。
     * -- GETTER --
     *  返回结果描述。
     */
    private final String message;

    /**
     * 构造单模块结果。
     *
     * @param module     模块编码
     * @param successful 是否同步成功
     * @param code       失败错误码
     * @param detail     结果描述
     */
    public BatchModuleSyncResult(String module, boolean successful, String code, String detail) {
        this(module, successful, false, code, detail);
    }

    /** 构造带锁冲突语义的单模块结果。 */
    public BatchModuleSyncResult(String module, boolean successful, boolean lockConflict,
            String code, String detail) {
        moduleCode = module;
        success = successful;
        conflict = lockConflict;
        errorCode = code;
        message = detail;
    }

}
