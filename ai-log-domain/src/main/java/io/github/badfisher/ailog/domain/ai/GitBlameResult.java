package io.github.badfisher.ailog.domain.ai;

import java.time.LocalDateTime;

import lombok.Getter;

/** 单行源码最后修改 Author 信息。 */
@Getter
public final class GitBlameResult {
    private final String authorName;
    private final LocalDateTime authorTime;

    public GitBlameResult(String name, LocalDateTime time) {
        authorName = name;
        authorTime = time;
    }
}
