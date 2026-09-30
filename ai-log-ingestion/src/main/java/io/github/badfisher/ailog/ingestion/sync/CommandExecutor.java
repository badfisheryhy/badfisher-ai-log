package io.github.badfisher.ailog.ingestion.sync;

import java.time.Duration;
import java.util.List;

/** 外部命令执行端口，测试时可替换，避免真实调用 rsync。 */
public interface CommandExecutor {

    /**
     * 执行外部命令并收集输出。
     *
     * @param command 命令及参数列表
     * @param timeout 超时时间
     * @return 命令执行结果
     */
    CommandResult execute(List<String> command, Duration timeout);
}
