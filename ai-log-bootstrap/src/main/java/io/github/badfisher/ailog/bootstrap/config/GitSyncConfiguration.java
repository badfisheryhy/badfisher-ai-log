package io.github.badfisher.ailog.bootstrap.config;

import io.github.badfisher.ailog.application.tool.SourceCodeSyncTool;
import io.github.badfisher.ailog.bootstrap.integration.git.GitRepositorySyncService;
import io.github.badfisher.ailog.bootstrap.integration.git.GitSyncProperties;
import io.github.badfisher.ailog.bootstrap.integration.git.GitWorkspacePathResolver;
import io.github.badfisher.ailog.bootstrap.integration.script.BundledScriptInstaller;
import io.github.badfisher.ailog.ingestion.sync.CommandExecutor;
import io.github.badfisher.ailog.ingestion.sync.SystemCommandExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 只注册源码同步依赖；启动时不拉取仓库，也不注册新的调度任务。 */
@Configuration
@EnableConfigurationProperties(GitSyncProperties.class)
public class GitSyncConfiguration {

    /** 将JAR内脚本释放到配置的运行目录，供日志及源码同步共同使用。 */
    @Bean
    public BundledScriptInstaller bundledScriptInstaller() {
        return new BundledScriptInstaller();
    }

    /** Git同步和AI源码读取共用同一目录结构及安全校验。 */
    @Bean
    public GitWorkspacePathResolver gitWorkspacePathResolver(GitSyncProperties properties) {
        return new GitWorkspacePathResolver(properties);
    }

    /** 日志同步通过应用层端口复用独立Git方法，不增加新的线程池或调度器。 */
    @Bean
    public SourceCodeSyncTool sourceCodeSyncTool(GitRepositorySyncService service) {
        return service::syncLatest;
    }

    /** 复用现有有界命令执行器，与日志同步 Bean 名称隔离。 */
    @Bean(name = "gitSyncCommandExecutor")
    public CommandExecutor gitSyncCommandExecutor() {
        return new SystemCommandExecutor();
    }
}
