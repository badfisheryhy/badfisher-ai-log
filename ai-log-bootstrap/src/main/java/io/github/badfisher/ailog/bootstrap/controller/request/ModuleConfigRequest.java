package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 新增或修改日志模块配置的请求体。 */
@Schema(name = "日志模块配置请求")
@Getter
@Setter
public class ModuleConfigRequest {

    @NotBlank(message = "环境编码不能为空")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,31}$", message = "环境编码格式不正确")
    @Schema(description = "环境编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "prod")
    private String environment;

    @NotBlank(message = "系统编码不能为空")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$", message = "系统编码格式不正确")
    @Schema(description = "系统编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "demo")
    private String systemCode;

    @NotBlank(message = "模块编码不能为空")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$", message = "模块编码格式不正确")
    @Schema(description = "模块编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "service")
    private String moduleCode;

    @NotNull(message = "启用状态不能为空")
    @Schema(description = "是否启用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean enabled = true;

    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", message = "日志服务器格式不正确")
    @Schema(description = "SSH 模式必填：日志服务器 IP 或域名")
    private String serverHost;

    @Min(value = 1, message = "SSH端口不能小于1")
    @Max(value = 65535, message = "SSH端口不能大于65535")
    @Schema(description = "SSH 端口，本地模式不使用", example = "22")
    private Integer serverPort = 22;

    @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_-]{0,63}$", message = "SSH用户格式不正确")
    @Schema(description = "SSH 模式必填：只读用户")
    private String sshUsername;

    @Size(max = 128, message = "SSH凭据引用不能超过128个字符")
    @Schema(description = "SSH凭据引用，不是凭据正文")
    private String credentialRef;

    @NotBlank(message = "日志目录（本地模式为服务端绝对路径）不能为空")
    @Size(max = 512, message = "日志目录（本地模式为服务端绝对路径）不能超过512个字符")
    @Schema(description = "日志目录（本地模式为服务端绝对路径）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String remoteDirectory;

    @NotBlank(message = "日志文件前缀不能为空")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$", message = "日志文件前缀格式不正确")
    @Schema(description = "日志文件前缀", requiredMode = Schema.RequiredMode.REQUIRED)
    private String logFilePrefix = "app";

    @NotNull(message = "ERROR日志同步开关不能为空")
    @Schema(description = "是否同步ERROR日志", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean syncErrorLog = true;

    @NotNull(message = "全量日志同步开关不能为空")
    @Schema(description = "是否同步全量日志", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean syncAllLog = false;

    @Size(max = 128, message = "本地目录别名不能超过128个字符")
    @Schema(description = "本地模块单级目录别名")
    private String localSubDirectory;

    @NotBlank(message = "解析器配置不能为空")
    @Pattern(regexp = "(?i)^JAVA$", message = "当前只支持JAVA解析器")
    @Schema(description = "解析器配置", requiredMode = Schema.RequiredMode.REQUIRED, example = "JAVA")
    private String parserProfile = "JAVA";

    @NotBlank(message = "分析模块不能为空")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$", message = "分析模块格式不正确")
    @Schema(description = "分析模块", requiredMode = Schema.RequiredMode.REQUIRED, example = "default")
    private String analysisModule = "default";

    @NotNull(message = "代码同步开关不能为空")
    @Schema(description = "是否在AI分析前同步Git源码", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean codeSyncEnabled = false;

    @Size(max = 1024, message = "Git仓库地址不能超过1024个字符")
    @Schema(description = "Git仓库地址默认使用HTTPS；HTTP同步须由部署显式开启，不得夹带凭据、查询参数或片段")
    private String gitRepositoryUrl;

    @Size(max = 128, message = "Git分支不能超过128个字符")
    @Schema(description = "Git目标分支")
    private String gitBranch;

    @NotNull(message = "同步优先级不能为空")
    @PositiveOrZero(message = "同步优先级不能小于0")
    @Schema(description = "同步优先级，小值优先", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer syncPriority = 100;

    @Size(max = 512, message = "备注不能超过512个字符")
    @Schema(description = "备注")
    private String remark;

    @PositiveOrZero(message = "乐观锁版本不能小于0")
    @Schema(description = "修改时必填；详情接口返回的乐观锁版本")
    private Integer lockVersion;
}
