package io.github.badfisher.ailog.application.path;

import java.nio.file.Path;

import io.github.badfisher.ailog.domain.sync.LogChannel;
import lombok.Getter;

/** 单模块单日的本地目录集合。 */
@Getter
public final class LogLocalPaths {

    /** 模块当日的根目录。
     * -- GETTER --
     *  返回模块当日的根目录。
     *
     */
    private final Path baseDirectory;

    /**
     * 构造本地目录集合。
     *
     * @param base 模块当日的根目录
     */
    public LogLocalPaths(Path base) {
        baseDirectory = base;
    }

    /**
     * 返回指定渠道的 incoming 目录。
     *
     * @param channel 日志渠道
     * @return incoming 目录路径
     */
    public Path incoming(LogChannel channel) {
        return baseDirectory.resolve("incoming").resolve(channel.getDirectory());
    }

    /**
     * 返回指定渠道的 ready 目录。
     *
     * @param channel 日志渠道
     * @return ready 目录路径
     */
    public Path ready(LogChannel channel) {
        return baseDirectory.resolve("ready").resolve(channel.getDirectory());
    }

    /**
     * 返回 metadata 目录。
     *
     * @return metadata 目录路径
     */
    public Path metadata() {
        return baseDirectory.resolve("metadata");
    }

}
