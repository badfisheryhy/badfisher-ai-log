package io.github.badfisher.ailog.persistence.config.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** AI 日志模块配置持久化实体。 */
@Data
@Schema(name = "模块配置", description = "AI日志模块动态配置")
@TableName("tb_ai_log_module_config")
public class AiLogModuleConfigEntity implements Serializable {

    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    @Schema(description = "数据库主键")
    private Long id;
    /** 环境标识。 */
    @Schema(description = "环境编码")
    private String environment;
    /** 系统编码。 */
    @Schema(description = "系统编码")
    private String systemCode;
    /** 模块编码。 */
    @Schema(description = "模块编码")
    private String moduleCode;
    /** 是否启用。 */
    @Schema(description = "是否启用")
    private Boolean enabled;
    /** 服务器主机。 */
    @Schema(description = "日志服务器IP或域名")
    private String serverHost;
    /** SSH 端口。 */
    @Schema(description = "SSH端口")
    private Integer serverPort;
    /** SSH 用户名。 */
    @Schema(description = "SSH只读用户")
    private String sshUsername;
    /** 密钥凭据引用。 */
    @Schema(description = "SSH凭证引用")
    private String credentialRef;
    /** 远端日志目录。 */
    @Schema(description = "远程日志目录")
    private String remoteDirectory;
    /** 日志文件名前缀。 */
    @Schema(description = "日志文件前缀")
    private String logFilePrefix;
    /** 是否同步 ERROR 日志。 */
    @Schema(description = "同步err日志")
    private Boolean syncErrorLog;
    /** 是否同步全部日志。 */
    @Schema(description = "同步all日志")
    private Boolean syncAllLog;
    /** 本地模块单级目录别名，为空时使用module_code。 */
    @Schema(description = "本地模块单级目录别名，为空时使用module_code")
    private String localSubDirectory;
    /** 解析器配置标识。 */
    @Schema(description = "解析配置")
    private String parserProfile;
    /** 分析模块编码。 */
    @Schema(description = "分析模块")
    private String analysisModule;
    /** 是否在 AI 分析前同步 Git 源码。 */
    @Schema(description = "是否在AI分析前同步Git源码")
    private Boolean codeSyncEnabled;
    /** Git 仓库地址，不包含凭证。 */
    @Schema(description = "Git仓库地址，不包含凭证")
    private String gitRepositoryUrl;
    /** Git 目标分支。 */
    @Schema(description = "Git目标分支")
    private String gitBranch;
    /** 同步优先级，数值小优先。 */
    @Schema(description = "同步优先级，小值优先")
    private Integer syncPriority;
    /** 备注。 */
    @Schema(description = "备注")
    private String remark;
    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
    /** 创建用户 ID。 */
    @Schema(description = "创建用户ID")
    private Integer createUserId;
    /** 创建用户名。 */
    @Schema(description = "创建用户名")
    private String createUserName;
    /** 更新用户 ID。 */
    @Schema(description = "更新用户ID")
    private Integer updateUserId;
    /** 更新用户名。 */
    @Schema(description = "更新用户名")
    private String updateUserName;
    /** 乐观锁版本。 */
    @Schema(description = "乐观锁版本")
    private Integer lockVersion;
}
