package io.github.badfisher.ailog.bootstrap.integration.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import io.github.badfisher.ailog.domain.config.LogModuleConfig;

/**
 * 统一解析Git同步和AI源码读取使用的本机仓库目录。
 *
 * <p>目录结构固定为 {@code workspace/environment/systemCode/moduleCode}。同步流程只创建
 * workspace、环境和系统三级父目录，具体模块目录仍由受控Git脚本创建；AI读取流程只接受
 * 已存在、可读且没有通过符号链接越出workspace的模块目录。</p>
 */
public final class GitWorkspacePathResolver {

    private final GitSyncProperties properties;

    /**
     * 创建工作区路径解析器，构造阶段不创建或访问文件系统。
     *
     * @param configuredProperties Git同步配置
     */
    public GitWorkspacePathResolver(GitSyncProperties configuredProperties) {
        if (configuredProperties == null) {
            throw new IllegalArgumentException("Git同步配置不能为空");
        }
        properties = configuredProperties;
    }

    /**
     * 准备Git脚本使用的环境、系统级工作区。
     *
     * @param module 模块配置
     * @return Git脚本的工作区参数，模块目录由脚本在该目录下创建
     * @throws IOException 创建或检查目录失败
     */
    public Path prepareWorkspace(LogModuleConfig module) throws IOException {
        if (module == null) {
            throw new IllegalArgumentException("模块配置不能为空");
        }
        validateCoordinates(module.getEnvironment(), module.getSystemCode(),
                module.getModuleCode());
        Path root = configuredRoot();
        Files.createDirectories(root);
        root = root.toRealPath();
        Path scope = root;
        for (String segment : Arrays.asList(module.getEnvironment(), module.getSystemCode())) {
            scope = scope.resolve(segment);
            if (Files.isSymbolicLink(scope)) {
                throw new IllegalArgumentException("Git范围目录不能是符号链接");
            }
            Files.createDirectories(scope);
            if (!scope.toRealPath().startsWith(root)) {
                throw new IllegalArgumentException("Git范围目录越出工作区");
            }
        }
        Path target = scope.resolve(module.getModuleCode());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(target) || !target.toRealPath().startsWith(root))) {
            throw new IllegalArgumentException("Git模块目录越出工作区或是符号链接");
        }
        return scope;
    }

    /**
     * 根据单次AI证据的环境、系统和模块解析已同步的源码仓库。
     *
     * @param environment 环境编码
     * @param systemCode  系统编码
     * @param moduleCode  模块编码
     * @return 已存在的模块源码仓库真实路径
     */
    public Path resolveExistingRepository(String environment, String systemCode,
            String moduleCode) {
        validateCoordinates(environment, systemCode, moduleCode);
        try {
            Path root = configuredRoot();
            if (!Files.isDirectory(root) || !Files.isReadable(root)) {
                throw new IllegalStateException("Git源码工作区不存在或不可读");
            }
            root = root.toRealPath();
            Path repository = root;
            for (String segment : Arrays.asList(environment, systemCode, moduleCode)) {
                Path candidate = repository.resolve(segment);
                if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate)
                        || !Files.isReadable(candidate)) {
                    throw new IllegalStateException("AI源码仓库不存在、不可读或是符号链接");
                }
                repository = candidate.toRealPath();
                if (!repository.startsWith(root)) {
                    throw new IllegalStateException("AI源码仓库越出Git工作区");
                }
            }
            return repository;
        } catch (IOException exception) {
            throw new IllegalStateException("AI源码仓库路径检查失败");
        }
    }

    /** 校验目录坐标，禁止路径分隔符、上级目录和控制字符进入路径拼接。 */
    void validateCoordinates(String environment, String systemCode, String moduleCode) {
        for (String segment : Arrays.asList(environment, systemCode, moduleCode)) {
            if (segment == null || !segment.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("环境、系统和模块必须是安全的单级目录名");
            }
        }
    }

    private Path configuredRoot() {
        String workspace = properties.getWorkspace();
        if (workspace == null || workspace.trim().isEmpty()) {
            throw new IllegalArgumentException("Git工作区不能为空");
        }
        Path root = Paths.get(workspace.trim()).toAbsolutePath().normalize();
        if (root.getParent() == null) {
            throw new IllegalArgumentException("不能使用文件系统根目录作为Git工作区");
        }
        return root;
    }
}
