package io.github.badfisher.ailog.bootstrap.controller.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 日志模块配置管理返回数据。 */
@Schema(name = "日志模块配置")
@Getter
@Setter
public class ModuleConfigResponse {

    @Schema(description = "主键")
    private Long id;
    @Schema(description = "环境编码")
    private String environment;
    @Schema(description = "系统编码")
    private String systemCode;
    @Schema(description = "模块编码")
    private String moduleCode;
    @Schema(description = "是否启用")
    private Boolean enabled;
    @Schema(description = "日志服务器IP或域名")
    private String serverHost;
    @Schema(description = "SSH端口")
    private Integer serverPort;
    @Schema(description = "SSH只读用户")
    private String sshUsername;
    @Schema(description = "SSH凭据引用，不是凭据正文")
    private String credentialRef;
    @Schema(description = "远程日志目录")
    private String remoteDirectory;
    @Schema(description = "日志文件前缀")
    private String logFilePrefix;
    @Schema(description = "是否同步ERROR日志")
    private Boolean syncErrorLog;
    @Schema(description = "是否同步全量日志")
    private Boolean syncAllLog;
    @Schema(description = "本地模块单级目录别名")
    private String localSubDirectory;
    @Schema(description = "解析器配置")
    private String parserProfile;
    @Schema(description = "分析模块")
    private String analysisModule;
    @Schema(description = "是否在AI分析前同步Git源码")
    private Boolean codeSyncEnabled;
    @Schema(description = "Git仓库地址，不包含凭证")
    private String gitRepositoryUrl;
    @Schema(description = "Git目标分支")
    private String gitBranch;
    @Schema(description = "同步优先级")
    private Integer syncPriority;
    @Schema(description = "备注")
    private String remark;
    @Schema(description = "创建用户ID")
    private Integer createUserId;
    @Schema(description = "创建用户名")
    private String createUserName;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "更新用户ID")
    private Integer updateUserId;
    @Schema(description = "更新用户名")
    private String updateUserName;
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
    @Schema(description = "乐观锁版本")
    private Integer lockVersion;
}
