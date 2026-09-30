package io.github.badfisher.ailog.bootstrap.integration.git;

import java.nio.file.Path;

import lombok.Getter;

/** 已成功检出的源码结果，不代表日志同步、AI 分析或报告已完成。 */
@Getter
public final class GitSyncResult {

    /** CLONED：首次克隆；UPDATED：提交更新；REUSED：远程提交未变化。 */
    private final String action;
    /** 本次实际检出的 Git 提交。 */
    private final String commitSha;
    /** 配置的目标分支。 */
    private final String branch;
    /** 当前模块的源码绝对目录。 */
    private final Path repositoryDirectory;

    /** 创建已通过脚本协议校验的成功结果。 */
    public GitSyncResult(String syncAction, String commit, String targetBranch, Path directory) {
        action = syncAction;
        commitSha = commit;
        branch = targetBranch;
        repositoryDirectory = directory;
    }
}
