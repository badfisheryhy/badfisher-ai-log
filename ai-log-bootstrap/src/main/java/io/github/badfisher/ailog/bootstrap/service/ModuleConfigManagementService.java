package io.github.badfisher.ailog.bootstrap.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.badfisher.ailog.bootstrap.controller.request.EnabledStatusRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.ModuleConfigRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.ModuleConfigResponse;
import io.github.badfisher.ailog.persistence.config.entity.AiLogModuleConfigEntity;
import io.github.badfisher.ailog.persistence.config.mapper.AiLogModuleConfigMapper;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 日志模块配置管理服务。 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class ModuleConfigManagementService {

    /** 环境编码最大长度。 */
    private static final int MAX_ENVIRONMENT_LENGTH = 32;
    /** 系统编码及分析模块最大长度。 */
    private static final int MAX_SYSTEM_CODE_LENGTH = 64;
    /** 模块相关单级字段最大长度。 */
    private static final int MAX_MODULE_FIELD_LENGTH = 128;
    /** 远程日志目录最大长度。 */
    private static final int MAX_REMOTE_DIRECTORY_LENGTH = 512;
    /** Git 仓库地址最大长度。 */
    private static final int MAX_GIT_URL_LENGTH = 1024;
    /** 备注最大长度。 */
    private static final int MAX_REMARK_LENGTH = 512;
    /** 当前唯一支持的日志解析器。 */
    private static final String JAVA_PARSER_PROFILE = "JAVA";

    /** 单级目录和业务编码允许的字符。 */
    private static final Pattern SAFE_SEGMENT_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9_.-]*$");
    /** 日志服务器地址允许的字符。 */
    private static final Pattern SERVER_HOST_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]*$");
    /** SSH 用户名允许的字符。 */
    private static final Pattern SSH_USERNAME_PATTERN = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_-]*$");
    /** 凭据引用必须是凭据目录下的单级名称。 */
    private static final Pattern CREDENTIAL_REF_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9_.-]*$");
    /** Git 分支允许的基础字符。 */
    private static final Pattern GIT_BRANCH_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$");

    private final AiLogModuleConfigMapper configMapper;
    private final ManagementActorProvider actorProvider;
    private final io.github.badfisher.ailog.application.config.LogSyncProperties syncProperties;

    /**
     * 创建模块配置管理服务。
     *
     * @param mapper        模块配置 Mapper
     * @param actorProviderValue 当前用户提供器
     */
    public ModuleConfigManagementService(AiLogModuleConfigMapper mapper,
            ManagementActorProvider actorProviderValue,
            io.github.badfisher.ailog.application.config.LogSyncProperties syncProperties) {
        configMapper = mapper;
        actorProvider = actorProviderValue;
        this.syncProperties = syncProperties;
    }

    /** 按前端筛选条件查询全部匹配的模块配置。 */
    @Transactional(readOnly = true)
    public List<ModuleConfigResponse> list(String environment, String systemCode,
            String moduleCode, Boolean enabled) {
        String normalizedEnvironment = normalizeOptionalSegment(environment,
                "环境编码", MAX_ENVIRONMENT_LENGTH);
        String normalizedSystemCode = normalizeOptionalSegment(systemCode,
                "系统编码", MAX_SYSTEM_CODE_LENGTH);
        String normalizedModuleCode = normalizeOptionalSegment(moduleCode,
                "模块编码", MAX_MODULE_FIELD_LENGTH);
        List<AiLogModuleConfigEntity> entities = configMapper.selectManagementList(
                normalizedEnvironment, normalizedSystemCode, normalizedModuleCode, enabled);
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        List<ModuleConfigResponse> responses = new ArrayList<ModuleConfigResponse>(entities.size());
        for (AiLogModuleConfigEntity entity : entities) {
            responses.add(toResponse(entity));
        }
        return responses;
    }

    /** 按主键查询模块配置详情。 */
    @Transactional(readOnly = true)
    public ModuleConfigResponse getDetail(long id) {
        return toResponse(requireExisting(id));
    }

    /** 新增模块配置并记录创建、更新用户快照。 */
    @Transactional(rollbackFor = Exception.class)
    public ModuleConfigResponse create(ModuleConfigRequest request) {
        AiLogModuleConfigEntity entity = buildBusinessEntity(request);
        AiLogModuleConfigEntity existing = findByIdentity(entity, false);
        if (existing != null) {
            log.warn("event=module_config_create_conflict 模块配置新增前发现重复记录："
                            + "existingId={}，environment={}，systemCode={}，moduleCode={}",
                    existing.getId(), entity.getEnvironment(), entity.getSystemCode(),
                    entity.getModuleCode());
            throw duplicateModuleConfigException(existing, entity, null);
        }
        ManagementActor actor = actorProvider.requireActorIdentity();
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateUserId(actor.getUserId());
        entity.setCreateUserName(actor.getUsername());
        entity.setCreateTime(now);
        entity.setUpdateUserId(actor.getUserId());
        entity.setUpdateUserName(actor.getUsername());
        entity.setUpdateTime(now);
        entity.setLockVersion(Integer.valueOf(0));
        try {
            if (configMapper.insert(entity) != 1 || entity.getId() == null) {
                throw new BusinessException("模块配置新增失败，请稍后重试");
            }
        } catch (DuplicateKeyException exception) {
            existing = findByIdentity(entity, true);
            log.warn("event=module_config_create_conflict 模块配置新增冲突："
                            + "existingId={}，environment={}，systemCode={}，moduleCode={}，operator={}",
                    existing == null ? null : existing.getId(), entity.getEnvironment(),
                    entity.getSystemCode(), entity.getModuleCode(), actor.getDisplayName());
            throw duplicateModuleConfigException(existing, entity, exception);
        }
        log.info("event=module_config_created 模块配置新增成功：id={}，environment={}，"
                        + "systemCode={}，moduleCode={}，operator={}",
                entity.getId(), entity.getEnvironment(), entity.getSystemCode(),
                entity.getModuleCode(), actor.getDisplayName());
        return toResponse(entity);
    }

    /** 按乐观锁版本修改完整业务配置。 */
    @Transactional(rollbackFor = Exception.class)
    public ModuleConfigResponse update(long id, ModuleConfigRequest request) {
        AiLogModuleConfigEntity current = requireExisting(id);
        Integer expectedVersion = requireLockVersion(request == null
                ? null : request.getLockVersion());
        verifyCurrentVersion(current, expectedVersion);

        AiLogModuleConfigEntity update = buildBusinessEntity(request);
        verifyIdentityUnchanged(current, update);
        ManagementActor actor = actorProvider.requireActorIdentity();
        update.setId(Long.valueOf(id));
        update.setUpdateUserId(actor.getUserId());
        update.setUpdateUserName(actor.getUsername());
        update.setUpdateTime(LocalDateTime.now());
        update.setLockVersion(expectedVersion);
        try {
            if (configMapper.updateManagement(update) != 1) {
                throwConcurrentModification(id);
            }
        } catch (DuplicateKeyException exception) {
            log.warn("event=module_config_update_conflict 模块配置修改发生自然键冲突："
                            + "id={}，environment={}，systemCode={}，moduleCode={}，operator={}",
                    Long.valueOf(id), update.getEnvironment(), update.getSystemCode(),
                    update.getModuleCode(), actor.getDisplayName());
            throw new BusinessException("相同环境、系统和模块编码的配置已经存在", exception);
        }
        log.info("event=module_config_updated 模块配置修改成功：id={}，environment={}，"
                        + "systemCode={}，moduleCode={}，oldVersion={}，operator={}",
                Long.valueOf(id), update.getEnvironment(), update.getSystemCode(),
                update.getModuleCode(), expectedVersion, actor.getDisplayName());
        return reloadAfterWrite(id);
    }

    /** 按乐观锁版本启用或停用模块配置。 */
    @Transactional(rollbackFor = Exception.class)
    public ModuleConfigResponse updateEnabled(long id, EnabledStatusRequest request) {
        if (request == null || request.getEnabled() == null) {
            throw new BusinessException("启用状态不能为空");
        }
        Integer expectedVersion = requireLockVersion(request.getLockVersion());
        AiLogModuleConfigEntity current = requireExisting(id);
        verifyCurrentVersion(current, expectedVersion);
        if (Boolean.TRUE.equals(request.getEnabled())) {
            validatePersistedConfigurationForEnable(current);
        }

        ManagementActor actor = actorProvider.requireActorIdentity();
        int updated = configMapper.updateEnabled(Long.valueOf(id), request.getEnabled(),
                actor.getUserId(), actor.getUsername(), expectedVersion);
        if (updated != 1) {
            throwConcurrentModification(id);
        }
        log.info("event=module_config_enabled_changed 模块配置启停状态修改成功："
                        + "id={}，enabled={}，oldVersion={}，operator={}",
                Long.valueOf(id), request.getEnabled(), expectedVersion,
                actor.getDisplayName());
        return reloadAfterWrite(id);
    }

    /** 按乐观锁版本软删除模块配置。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(long id, Integer lockVersion) {
        EnabledStatusRequest request = new EnabledStatusRequest();
        request.setEnabled(Boolean.FALSE);
        request.setLockVersion(lockVersion);
        updateEnabled(id, request);
    }

    /** 将前端请求转换为已经完成语义校验和规范化的持久化实体。 */
    private AiLogModuleConfigEntity buildBusinessEntity(ModuleConfigRequest request) {
        if (request == null) {
            throw new BusinessException("模块配置请求不能为空");
        }
        AiLogModuleConfigEntity entity = new AiLogModuleConfigEntity();
        entity.setEnvironment(normalizeRequiredSegment(request.getEnvironment(),
                "环境编码", MAX_ENVIRONMENT_LENGTH));
        entity.setSystemCode(normalizeRequiredSegment(request.getSystemCode(),
                "系统编码", MAX_SYSTEM_CODE_LENGTH));
        entity.setModuleCode(normalizeRequiredSegment(request.getModuleCode(),
                "模块编码", MAX_MODULE_FIELD_LENGTH));
        entity.setEnabled(requireBoolean(request.getEnabled(), "启用状态"));
        entity.setServerHost(syncProperties.isEnabled() ? normalizeServerHost(request.getServerHost()) : "");
        entity.setServerPort(syncProperties.isEnabled() ? requirePort(request.getServerPort()) : 22);
        entity.setSshUsername(syncProperties.isEnabled() ? normalizeSshUsername(request.getSshUsername()) : "");
        entity.setCredentialRef(normalizeCredentialRef(request.getCredentialRef()));
        entity.setRemoteDirectory(normalizeRemoteDirectory(request.getRemoteDirectory()));
        entity.setLogFilePrefix(normalizeRequiredSegment(request.getLogFilePrefix(),
                "日志文件前缀", MAX_MODULE_FIELD_LENGTH));
        entity.setSyncErrorLog(requireBoolean(request.getSyncErrorLog(),
                "ERROR日志同步开关"));
        entity.setSyncAllLog(requireBoolean(request.getSyncAllLog(),
                "全量日志同步开关"));
        validateLogChannels(entity.getSyncErrorLog(), entity.getSyncAllLog());
        entity.setLocalSubDirectory(normalizeOptionalSegment(request.getLocalSubDirectory(),
                "本地目录别名", MAX_MODULE_FIELD_LENGTH));
        entity.setParserProfile(normalizeParserProfile(request.getParserProfile()));
        entity.setAnalysisModule(normalizeRequiredSegment(request.getAnalysisModule(),
                "分析模块", MAX_SYSTEM_CODE_LENGTH));
        entity.setCodeSyncEnabled(requireBoolean(request.getCodeSyncEnabled(),
                "代码同步开关"));
        entity.setGitRepositoryUrl(normalizeOptionalText(request.getGitRepositoryUrl(),
                "Git仓库地址", MAX_GIT_URL_LENGTH));
        entity.setGitBranch(normalizeOptionalText(request.getGitBranch(),
                "Git分支", MAX_MODULE_FIELD_LENGTH));
        validateGitConfiguration(entity.getCodeSyncEnabled(), entity.getGitRepositoryUrl(),
                entity.getGitBranch());
        entity.setSyncPriority(requireNonNegative(request.getSyncPriority(), "同步优先级"));
        entity.setRemark(normalizeOptionalText(request.getRemark(), "备注", MAX_REMARK_LENGTH));
        return entity;
    }

    /** 查询存在的配置，并统一校验主键。 */
    private AiLogModuleConfigEntity requireExisting(long id) {
        if (id <= 0L) {
            throw new BusinessException("模块配置ID必须大于0");
        }
        AiLogModuleConfigEntity entity = configMapper.selectManagementById(Long.valueOf(id));
        if (entity == null) {
            throw new BusinessException("未找到模块配置，ID：" + id);
        }
        return entity;
    }

    /** 重新读取写入后的数据库记录。 */
    private ModuleConfigResponse reloadAfterWrite(long id) {
        AiLogModuleConfigEntity entity = configMapper.selectManagementById(Long.valueOf(id));
        if (entity == null) {
            throw new BusinessException("模块配置写入后未能读取最新数据，ID：" + id);
        }
        return toResponse(entity);
    }

    /** 校验前端版本与当前版本一致。 */
    private static void verifyCurrentVersion(AiLogModuleConfigEntity current,
            Integer expectedVersion) {
        if (!Objects.equals(current.getLockVersion(), expectedVersion)) {
            throw new BusinessException("模块配置已被其他用户修改，请刷新后重试；当前版本："
                    + readableVersion(current.getLockVersion()));
        }
    }

    /** 写 SQL 未命中时重新读取最新版本并抛出明确的并发冲突。 */
    private void throwConcurrentModification(long id) {
        AiLogModuleConfigEntity latest = configMapper.selectManagementById(Long.valueOf(id));
        if (latest == null) {
            throw new BusinessException("模块配置不存在或已删除，ID：" + id);
        }
        throw new BusinessException("模块配置已被其他用户修改，请刷新后重试；当前版本："
                + readableVersion(latest.getLockVersion()));
    }

    /** 校验并返回修改操作要求的乐观锁版本。 */
    private static Integer requireLockVersion(Integer lockVersion) {
        if (lockVersion == null) {
            throw new BusinessException("乐观锁版本不能为空");
        }
        if (lockVersion.intValue() < 0) {
            throw new BusinessException("乐观锁版本不能小于0");
        }
        return lockVersion;
    }

    /** 两个日志通道不能同时关闭。 */
    private static void validateLogChannels(Boolean syncErrorLog, Boolean syncAllLog) {
        if (!Boolean.TRUE.equals(syncErrorLog) && !Boolean.TRUE.equals(syncAllLog)) {
            throw new BusinessException("ERROR日志和全量日志不能同时关闭");
        }
    }

    /** 模块身份被下游任务持久化，普通配置修改不得原地变更。 */
    private static void verifyIdentityUnchanged(AiLogModuleConfigEntity current,
            AiLogModuleConfigEntity update) {
        boolean changed = !Objects.equals(current.getEnvironment(), update.getEnvironment())
                || !Objects.equals(current.getSystemCode(), update.getSystemCode())
                || !Objects.equals(current.getModuleCode(), update.getModuleCode());
        if (changed) {
            throw new BusinessException("环境、系统和模块编码属于配置身份，不支持直接修改；"
                    + "请新增配置后停用旧配置");
        }
    }

    /** 启用历史配置前复用当前写入规则，停用操作不受脏数据阻断。 */
    private void validatePersistedConfigurationForEnable(
            AiLogModuleConfigEntity current) {
        normalizeRequiredSegment(current.getEnvironment(), "环境编码", MAX_ENVIRONMENT_LENGTH);
        normalizeRequiredSegment(current.getSystemCode(), "系统编码", MAX_SYSTEM_CODE_LENGTH);
        normalizeRequiredSegment(current.getModuleCode(), "模块编码", MAX_MODULE_FIELD_LENGTH);
        if (syncProperties.isEnabled()) {
            normalizeServerHost(current.getServerHost());
            requirePort(current.getServerPort());
            normalizeSshUsername(current.getSshUsername());
        }
        normalizeCredentialRef(current.getCredentialRef());
        normalizeRemoteDirectory(current.getRemoteDirectory());
        normalizeRequiredSegment(current.getLogFilePrefix(), "日志文件前缀",
                MAX_MODULE_FIELD_LENGTH);
        Boolean syncErrorLog = requireBoolean(current.getSyncErrorLog(), "ERROR日志同步开关");
        Boolean syncAllLog = requireBoolean(current.getSyncAllLog(), "全量日志同步开关");
        validateLogChannels(syncErrorLog, syncAllLog);
        normalizeOptionalSegment(current.getLocalSubDirectory(), "本地目录别名",
                MAX_MODULE_FIELD_LENGTH);
        normalizeParserProfile(current.getParserProfile());
        normalizeRequiredSegment(current.getAnalysisModule(), "分析模块",
                MAX_SYSTEM_CODE_LENGTH);
        Boolean codeSyncEnabled = requireBoolean(current.getCodeSyncEnabled(), "代码同步开关");
        String repositoryUrl = normalizeOptionalText(current.getGitRepositoryUrl(),
                "Git仓库地址", MAX_GIT_URL_LENGTH);
        String branch = normalizeOptionalText(current.getGitBranch(), "Git分支",
                MAX_MODULE_FIELD_LENGTH);
        validateGitConfiguration(codeSyncEnabled, repositoryUrl, branch);
        requireNonNegative(current.getSyncPriority(), "同步优先级");
        normalizeOptionalText(current.getRemark(), "备注", MAX_REMARK_LENGTH);
    }

    /** 校验 Git 开关、仓库地址和分支是完整的一组配置。 */
    private static void validateGitConfiguration(Boolean codeSyncEnabled,
            String repositoryUrl, String branch) {
        boolean hasRepository = hasText(repositoryUrl);
        boolean hasBranch = hasText(branch);
        if (Boolean.TRUE.equals(codeSyncEnabled) && (!hasRepository || !hasBranch)) {
            throw new BusinessException("启用代码同步时Git仓库地址和分支不能为空");
        }
        if (hasRepository != hasBranch) {
            throw new BusinessException("Git仓库地址和分支必须同时填写或同时留空");
        }
        if (!hasRepository) {
            return;
        }
        validateGitRepositoryUrl(repositoryUrl);
        validateGitBranch(branch);
    }

    /** 与源码同步入口一致，只接受不带凭据的 HTTP 仓库地址。 */
    private static void validateGitRepositoryUrl(String repositoryUrl) {
        if (containsWhitespaceOrControl(repositoryUrl)) {
            throw new BusinessException("Git仓库地址不能包含空白或控制字符");
        }
        URI uri;
        try {
            uri = new URI(repositoryUrl);
        } catch (URISyntaxException exception) {
            throw new BusinessException("Git仓库地址格式不正确", exception);
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new BusinessException("Git仓库地址只支持HTTP(S)协议");
        }
        if (!hasText(uri.getHost()) || !hasText(uri.getPath())
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new BusinessException("Git仓库地址必须包含有效主机和路径，不能包含查询参数或片段");
        }
        if (uri.getRawUserInfo() != null) {
            throw new BusinessException("Git仓库地址不得包含用户名、密码或Token");
        }
    }

    /** 校验 Git 分支不包含 Git 禁止或容易产生歧义的格式。 */
    private static void validateGitBranch(String branch) {
        boolean invalid = !GIT_BRANCH_PATTERN.matcher(branch).matches()
                || branch.contains("..")
                || branch.contains("//")
                || branch.contains("@{")
                || branch.endsWith("/")
                || branch.endsWith(".")
                || branch.endsWith(".lock")
                || branch.contains("/.");
        if (invalid) {
            throw new BusinessException("Git分支格式不安全");
        }
    }

    /** 远程目录必须是无控制字符、无回退路径段的绝对 POSIX 路径。 */
    private String normalizeRemoteDirectory(String value) {
        String directory = normalizeRequiredText(value, "日志目录", MAX_REMOTE_DIRECTORY_LENGTH);
        if (syncProperties.isEnabled()) {
            if (!directory.startsWith("/") || directory.contains("\n") || directory.contains("\r")
                    || directory.contains("/../") || directory.endsWith("/..")) {
                throw new BusinessException("远程日志目录必须是安全的绝对路径");
            }
            return directory;
        }
        java.nio.file.Path path = java.nio.file.Path.of(directory);
        if (!path.isAbsolute() || !java.nio.file.Files.isDirectory(path)) {
            throw new BusinessException("本地日志目录必须是已存在的绝对路径");
        }
        try {
            io.github.badfisher.ailog.application.path.LogPathAccessPolicy.requireDirectory(
                    path, syncProperties.getAllowedLogRoots());
            return path.normalize().toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(exception.getMessage());
        }
    }
    /** 规范化并校验日志服务器。 */
    private static String normalizeServerHost(String value) {
        String normalized = normalizeRequiredText(value, "日志服务器",
                MAX_MODULE_FIELD_LENGTH);
        if (!SERVER_HOST_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException("日志服务器格式不正确");
        }
        return normalized;
    }

    /** 规范化并校验 SSH 用户名。 */
    private static String normalizeSshUsername(String value) {
        String normalized = normalizeRequiredText(value, "SSH用户", MAX_SYSTEM_CODE_LENGTH);
        if (!SSH_USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException("SSH用户格式不正确");
        }
        return normalized;
    }

    /** 规范化 SSH 凭据引用，禁止将路径或凭据正文写入引用字段。 */
    private static String normalizeCredentialRef(String value) {
        String normalized = normalizeOptionalText(value, "SSH凭据引用",
                MAX_MODULE_FIELD_LENGTH);
        if (normalized != null && !CREDENTIAL_REF_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException("SSH凭据引用必须是安全的单级名称");
        }
        return normalized;
    }

    /** 当前运行链只允许 JAVA 解析器。 */
    private static String normalizeParserProfile(String value) {
        String normalized = normalizeRequiredText(value, "解析器配置",
                MAX_SYSTEM_CODE_LENGTH);
        if (!JAVA_PARSER_PROFILE.equalsIgnoreCase(normalized)) {
            throw new BusinessException("当前只支持JAVA解析器");
        }
        return JAVA_PARSER_PROFILE;
    }

    /** 规范化必填业务编码。 */
    private static String normalizeRequiredSegment(String value, String fieldName,
            int maxLength) {
        String normalized = normalizeRequiredText(value, fieldName, maxLength);
        if (!SAFE_SEGMENT_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(fieldName + "格式不正确");
        }
        return normalized;
    }

    /** 规范化可选业务编码。 */
    private static String normalizeOptionalSegment(String value, String fieldName,
            int maxLength) {
        String normalized = normalizeOptionalText(value, fieldName, maxLength);
        if (normalized != null && !SAFE_SEGMENT_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(fieldName + "格式不正确");
        }
        return normalized;
    }

    /** 规范化必填文本。 */
    private static String normalizeRequiredText(String value, String fieldName, int maxLength) {
        if (!hasText(value)) {
            throw new BusinessException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(fieldName + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    /** 规范化可选文本，空白文本按未配置处理。 */
    private static String normalizeOptionalText(String value, String fieldName, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(fieldName + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    /** 校验必填布尔值。 */
    private static Boolean requireBoolean(Boolean value, String fieldName) {
        if (value == null) {
            throw new BusinessException(fieldName + "不能为空");
        }
        return value;
    }

    /** 校验 SSH 端口。 */
    private static Integer requirePort(Integer value) {
        if (value == null) {
            throw new BusinessException("SSH端口不能为空");
        }
        if (value.intValue() < 1 || value.intValue() > 65535) {
            throw new BusinessException("SSH端口必须在1到65535之间");
        }
        return value;
    }

    /** 校验非负整数。 */
    private static Integer requireNonNegative(Integer value, String fieldName) {
        if (value == null) {
            throw new BusinessException(fieldName + "不能为空");
        }
        if (value.intValue() < 0) {
            throw new BusinessException(fieldName + "不能小于0");
        }
        return value;
    }

    /** 是否包含空白或控制字符。 */
    private static boolean containsWhitespaceOrControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current) || Character.isISOControl(current)) {
                return true;
            }
        }
        return false;
    }

    /** 是否包含控制字符。 */
    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /** 可读版本号。 */
    private static String readableVersion(Integer version) {
        return version == null ? "未知" : String.valueOf(version);
    }

    /** 按模块配置唯一键查询已有记录。 */
    private AiLogModuleConfigEntity findByIdentity(AiLogModuleConfigEntity entity,
            boolean lockForUpdate) {
        return configMapper.selectManagementByIdentity(entity.getEnvironment(),
                entity.getSystemCode(), entity.getModuleCode(), lockForUpdate);
    }

    /** 构造包含已有记录ID和唯一键信息的新增冲突提示。 */
    private static BusinessException duplicateModuleConfigException(
            AiLogModuleConfigEntity existing, AiLogModuleConfigEntity requested,
            DuplicateKeyException cause) {
        String message;
        if (existing != null && existing.getId() != null) {
            message = "模块配置已存在：ID=" + existing.getId()
                    + "，环境=" + existing.getEnvironment()
                    + "，系统=" + existing.getSystemCode()
                    + "，模块=" + existing.getModuleCode()
                    + "；请根据该ID查询详情后使用修改接口更新";
        } else {
            message = "模块配置已存在：环境=" + requested.getEnvironment()
                    + "，系统=" + requested.getSystemCode()
                    + "，模块=" + requested.getModuleCode()
                    + "；请刷新列表并使用已有记录的修改接口更新";
        }
        return cause == null ? new BusinessException(message)
                : new BusinessException(message, cause);
    }

    /** 将持久化实体转换为前端返回数据。 */
    private static ModuleConfigResponse toResponse(AiLogModuleConfigEntity entity) {
        ModuleConfigResponse response = new ModuleConfigResponse();
        response.setId(entity.getId());
        response.setEnvironment(entity.getEnvironment());
        response.setSystemCode(entity.getSystemCode());
        response.setModuleCode(entity.getModuleCode());
        response.setEnabled(entity.getEnabled());
        response.setServerHost(entity.getServerHost());
        response.setServerPort(entity.getServerPort());
        response.setSshUsername(entity.getSshUsername());
        response.setCredentialRef(entity.getCredentialRef());
        response.setRemoteDirectory(entity.getRemoteDirectory());
        response.setLogFilePrefix(entity.getLogFilePrefix());
        response.setSyncErrorLog(entity.getSyncErrorLog());
        response.setSyncAllLog(entity.getSyncAllLog());
        response.setLocalSubDirectory(entity.getLocalSubDirectory());
        response.setParserProfile(entity.getParserProfile());
        response.setAnalysisModule(entity.getAnalysisModule());
        response.setCodeSyncEnabled(entity.getCodeSyncEnabled());
        response.setGitRepositoryUrl(entity.getGitRepositoryUrl());
        response.setGitBranch(entity.getGitBranch());
        response.setSyncPriority(entity.getSyncPriority());
        response.setRemark(entity.getRemark());
        response.setCreateUserId(entity.getCreateUserId());
        response.setCreateUserName(entity.getCreateUserName());
        response.setCreateTime(entity.getCreateTime());
        response.setUpdateUserId(entity.getUpdateUserId());
        response.setUpdateUserName(entity.getUpdateUserName());
        response.setUpdateTime(entity.getUpdateTime());
        response.setLockVersion(entity.getLockVersion());
        return response;
    }
}
