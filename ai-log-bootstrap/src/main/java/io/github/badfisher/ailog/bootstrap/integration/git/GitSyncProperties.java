package io.github.badfisher.ailog.bootstrap.integration.git;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Git 源码同步的本机运行配置；仓库地址和分支仍由模块配置提供。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "badfisher.git-sync")
public class GitSyncProperties {

    /** 专用源码工作区，不得指向开发者正在编辑的工程。 */
    private String workspace;

    /** Bash 可执行文件；Windows 使用 Git Bash 的 bash.exe 绝对路径。 */
    private String bashExecutable = "bash";

    /** 整次脚本执行超时秒数，范围 1..3600，超时终止进程组。 */
    private int timeoutSeconds = 600;

    /** 默认只允许 HTTPS；仅可信环境可显式允许明文 HTTP。 */
    private boolean allowInsecureHttp;

    /** Git HTTP(S) 只读账号。 */
    private String username;

    /** HTTP密码或Token，不参与toString，也不写入命令行和日志。 */
    private String password;

    /** 允许接收上述账号的仓库主机名，不含协议、端口和路径。 */
    private List<String> allowedHosts = new ArrayList<String>();
}
