package io.github.badfisher.ailog.domain.ai;

import java.util.Optional;

/** 查询源码行最后修改 Author 的基础设施端口。 */
public interface GitBlameTool {
    Optional<GitBlameResult> tryBlame(AiTaskSourceLocation location);
}
