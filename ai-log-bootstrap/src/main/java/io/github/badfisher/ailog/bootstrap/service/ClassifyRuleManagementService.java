package io.github.badfisher.ailog.bootstrap.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.github.badfisher.ailog.bootstrap.controller.request.ClassifyRuleRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.EnabledStatusRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.ClassifyRuleResponse;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.issue.ProblemType;
import io.github.badfisher.ailog.domain.issue.RootCauseMatchTarget;
import io.github.badfisher.ailog.domain.issue.RootCauseRuleType;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogClassifyRuleEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogClassifyRuleMapper;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 根因分类规则管理服务。 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class ClassifyRuleManagementService {

    private static final String DEFAULT_ANALYSIS_MODULE = "default";
    private static final int MAX_PATTERN_LENGTH = 512;
    private static final int MAX_REASON_CODE_LENGTH = 128;
    private static final int MAX_REMARK_LENGTH = 512;
    private static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE;
    private static final Pattern REASON_CODE_PATTERN = Pattern.compile(
            "^[A-Z0-9][A-Z0-9_.-]{0,127}$");

    private final AiLogClassifyRuleMapper ruleMapper;
    private final ManagementActorProvider actorProvider;

    public ClassifyRuleManagementService(AiLogClassifyRuleMapper mapper,
            ManagementActorProvider managementActorProvider) {
        ruleMapper = mapper;
        actorProvider = managementActorProvider;
    }

    /** 按管理端筛选条件查询规则，包含已停用规则。 */
    @Transactional(readOnly = true)
    public List<ClassifyRuleResponse> list(String analysisModule, String category,
            String ruleType, Boolean enabled) {
        String normalizedModule = normalizeOptionalFilter(analysisModule);
        String normalizedCategory = normalizeOptionalEnumFilter(category, "根因分类");
        String normalizedRuleType = normalizeOptionalEnumFilter(ruleType, "规则类型");
        if (normalizedCategory != null) {
            requireCategory(normalizedCategory);
        }
        if (normalizedRuleType != null) {
            requireRuleType(normalizedRuleType);
        }
        List<AiLogClassifyRuleEntity> entities = ruleMapper.selectManagementList(
                normalizedModule, normalizedCategory, normalizedRuleType, enabled);
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        List<ClassifyRuleResponse> responses =
                new ArrayList<ClassifyRuleResponse>(entities.size());
        for (AiLogClassifyRuleEntity entity : entities) {
            responses.add(toResponse(entity));
        }
        return responses;
    }

    /** 查询单条分类规则详情。 */
    @Transactional(readOnly = true)
    public ClassifyRuleResponse detail(long ruleId) {
        return toResponse(requireRule(ruleId));
    }

    /** 新增分类规则并记录服务端操作人。 */
    @Transactional(rollbackFor = Exception.class)
    public ClassifyRuleResponse create(ClassifyRuleRequest request) {
        AiLogClassifyRuleEntity entity = buildValidatedEntity(request);
        AiLogClassifyRuleEntity existing = findByUniqueKey(entity, false);
        if (existing != null) {
            log.warn("event=classify_rule_create_conflict 分类规则新增前发现重复记录："
                            + "existingId={}，analysisModule={}，ruleType={}，reasonCode={}",
                    existing.getId(), entity.getAnalysisModule(), entity.getRuleType(),
                    existing.getReasonCode());
            throw duplicateRuleCreateException(existing, entity, null);
        }
        if (Boolean.TRUE.equals(entity.getEnabled())) {
            validateReasonCodeConsistency(entity, null);
        }
        ManagementActor actor = actorProvider.requireActorIdentity();
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateUserId(actor.getUserId());
        entity.setCreateUserName(actor.getUsername());
        entity.setUpdateUserId(actor.getUserId());
        entity.setUpdateUserName(actor.getUsername());
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setLockVersion(Integer.valueOf(0));
        try {
            if (ruleMapper.insert(entity) != 1 || entity.getId() == null) {
                throw new BusinessException("分类规则创建失败，请稍后重试");
            }
        } catch (DuplicateKeyException exception) {
            existing = findByUniqueKey(entity, true);
            log.warn("event=classify_rule_create_conflict 分类规则新增冲突："
                            + "existingId={}，analysisModule={}，ruleType={}，reasonCode={}，actor={}",
                    existing == null ? null : existing.getId(), entity.getAnalysisModule(),
                    entity.getRuleType(), existing == null ? null : existing.getReasonCode(),
                    actor.getDisplayName());
            throw duplicateRuleCreateException(existing, entity, exception);
        }
        log.info("event=classify_rule_created 分类规则已创建：ruleId={}, analysisModule={}, "
                        + "actor={}",
                entity.getId(), entity.getAnalysisModule(), actor.getDisplayName());
        return toResponse(entity);
    }

    /** 按前端读取到的版本修改完整规则。 */
    @Transactional(rollbackFor = Exception.class)
    public ClassifyRuleResponse update(long ruleId, ClassifyRuleRequest request) {
        requirePositiveRuleId(ruleId);
        int expectedVersion = requireLockVersion(request == null ? null : request.getLockVersion());
        AiLogClassifyRuleEntity entity = buildValidatedEntity(request);
        ManagementActor actor = actorProvider.requireActorIdentity();
        entity.setId(Long.valueOf(ruleId));
        entity.setUpdateUserId(actor.getUserId());
        entity.setUpdateUserName(actor.getUsername());
        entity.setLockVersion(Integer.valueOf(expectedVersion));
        if (Boolean.TRUE.equals(entity.getEnabled())) {
            validateReasonCodeConsistency(entity, Long.valueOf(ruleId));
        }
        try {
            if (ruleMapper.updateManagement(entity) != 1) {
                throwUpdateRejected(ruleId, expectedVersion);
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateRuleException(exception);
        }
        ClassifyRuleResponse response = detail(ruleId);
        log.info("event=classify_rule_updated 分类规则已修改：ruleId={}, lockVersion={}, "
                        + "actor={}",
                Long.valueOf(ruleId), response.getLockVersion(), actor.getDisplayName());
        return response;
    }

    /** 按前端读取到的版本启用或停用规则。 */
    @Transactional(rollbackFor = Exception.class)
    public ClassifyRuleResponse changeEnabled(long ruleId, EnabledStatusRequest request) {
        requirePositiveRuleId(ruleId);
        if (request == null || request.getEnabled() == null) {
            throw new BusinessException("启用状态不能为空");
        }
        int expectedVersion = requireLockVersion(request.getLockVersion());
        if (Boolean.TRUE.equals(request.getEnabled())) {
            AiLogClassifyRuleEntity current = requireRule(ruleId);
            verifyCurrentVersion(current, expectedVersion);
            validatePersistedRuleForEnable(current);
            // 启用目标状态生效，即使当前停用也必须按启用语义校验同 reasonCode 一致性。
            validateReasonCodeConsistency(current, Long.valueOf(ruleId));
        }
        ManagementActor actor = actorProvider.requireActorIdentity();
        int updated = ruleMapper.updateEnabled(Long.valueOf(ruleId), request.getEnabled(),
                actor.getUserId(), actor.getUsername(), Integer.valueOf(expectedVersion));
        if (updated != 1) {
            throwUpdateRejected(ruleId, expectedVersion);
        }
        ClassifyRuleResponse response = detail(ruleId);
        log.info("event=classify_rule_enabled_changed 分类规则启用状态已修改：ruleId={}, "
                        + "enabled={}, lockVersion={}, actor={}",
                Long.valueOf(ruleId), response.getEnabled(), response.getLockVersion(),
                actor.getDisplayName());
        return response;
    }

    /** 按乐观锁版本软删除分类规则。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(long ruleId, Integer lockVersion) {
        EnabledStatusRequest request = new EnabledStatusRequest();
        request.setEnabled(Boolean.FALSE);
        request.setLockVersion(lockVersion);
        changeEnabled(ruleId, request);
    }

    /** 构造已经完成全部业务校验和规范化的持久化实体。 */
    private static AiLogClassifyRuleEntity buildValidatedEntity(ClassifyRuleRequest request) {
        if (request == null) {
            throw new BusinessException("分类规则请求不能为空");
        }
        String analysisModule = hasText(request.getAnalysisModule())
                ? request.getAnalysisModule().trim() : DEFAULT_ANALYSIS_MODULE;
        String categoryText = normalizeRequired(request.getCategory(), "根因分类", 32);
        RootCauseCategory category = requireCategory(categoryText);
        String ruleTypeText = normalizeRequired(request.getRuleType(), "规则类型", 16);
        RootCauseRuleType ruleType = requireRuleType(ruleTypeText);
        String matchTargetText = normalizeRequired(request.getMatchTarget(), "匹配目标", 16);
        requireMatchTarget(matchTargetText);
        String pattern = normalizeRequired(request.getPattern(), "匹配内容", MAX_PATTERN_LENGTH);
        if (ruleType == RootCauseRuleType.REGEX) {
            validateRegex(pattern);
        } else {
            pattern = pattern.toLowerCase(Locale.ROOT);
        }
        if (request.getPriority() == null || request.getPriority().intValue() < 0) {
            throw new BusinessException("优先级不能为空且不能小于0");
        }
        if (request.getEnabled() == null || request.getExpected() == null
                || request.getAiRequired() == null) {
            throw new BusinessException("启用状态、预期业务标识和AI分析标识不能为空");
        }
        if (Boolean.TRUE.equals(request.getExpected()) && category != RootCauseCategory.BUSINESS) {
            throw new BusinessException("预期业务规则必须使用BUSINESS分类");
        }
        if (Boolean.TRUE.equals(request.getExpected())
                && Boolean.TRUE.equals(request.getAiRequired())) {
            throw new BusinessException("预期业务规则不能同时要求AI分析");
        }
        String reasonCode = normalizeRequired(request.getReasonCode(), "原因编码",
                MAX_REASON_CODE_LENGTH);
        if (!REASON_CODE_PATTERN.matcher(reasonCode).matches()) {
            throw new BusinessException("原因编码格式不正确");
        }

        AiLogClassifyRuleEntity entity = new AiLogClassifyRuleEntity();
        entity.setAnalysisModule(analysisModule);
        entity.setCategory(categoryText);
        entity.setRuleType(ruleTypeText);
        entity.setMatchTarget(matchTargetText);
        entity.setPattern(pattern);
        entity.setPriority(request.getPriority());
        entity.setEnabled(request.getEnabled());
        entity.setExpected(request.getExpected());
        entity.setAiRequired(request.getAiRequired());
        entity.setReasonCode(reasonCode);
        entity.setRemark(normalizeOptional(request.getRemark(), "备注", MAX_REMARK_LENGTH));
        return entity;
    }

    private AiLogClassifyRuleEntity requireRule(long ruleId) {
        requirePositiveRuleId(ruleId);
        AiLogClassifyRuleEntity entity = ruleMapper.selectManagementById(Long.valueOf(ruleId));
        if (entity == null) {
            throw new BusinessException("未找到分类规则，规则ID：" + ruleId);
        }
        return entity;
    }

    private void throwUpdateRejected(long ruleId, int expectedVersion) {
        AiLogClassifyRuleEntity current = ruleMapper.selectManagementById(Long.valueOf(ruleId));
        if (current == null) {
            throw new BusinessException("未找到分类规则，规则ID：" + ruleId);
        }
        throw new BusinessException("分类规则已被其他用户更新，请刷新后重试；提交版本："
                + expectedVersion + "，当前版本：" + current.getLockVersion());
    }

    /** 启用前使用与新增、修改一致的规则校验，避免历史脏规则进入分类链路。 */
    private static void validatePersistedRuleForEnable(AiLogClassifyRuleEntity current) {
        ClassifyRuleRequest request = new ClassifyRuleRequest();
        request.setAnalysisModule(current.getAnalysisModule());
        request.setCategory(current.getCategory());
        request.setRuleType(current.getRuleType());
        request.setMatchTarget(current.getMatchTarget());
        request.setPattern(current.getPattern());
        request.setPriority(current.getPriority());
        request.setEnabled(Boolean.TRUE);
        request.setExpected(current.getExpected());
        request.setAiRequired(current.getAiRequired());
        request.setReasonCode(current.getReasonCode());
        request.setRemark(current.getRemark());
        buildValidatedEntity(request);
    }

    /** 启用前校验前端版本与数据库当前版本一致。 */
    private static void verifyCurrentVersion(AiLogClassifyRuleEntity current,
            int expectedVersion) {
        Integer currentVersion = current.getLockVersion();
        if (currentVersion == null || currentVersion.intValue() != expectedVersion) {
            throw new BusinessException("分类规则已被其他用户更新，请刷新后重试；提交版本："
                    + expectedVersion + "，当前版本：" + currentVersion);
        }
    }

    /**
     * 校验同一分析模块下相同 reasonCode 的已启用规则分类语义一致。
     *
     * <p>同一 reasonCode 聚合到同一业务分类，其核心分类语义（category、expected、
     * aiRequired）必须一致，否则会导致同一聚合身份的分类事实随最后 flush 漂移。
     * 调用方负责按目标启用状态决定是否校验：changeEnabled(false) 直接放行。</p>
     */
    private void validateReasonCodeConsistency(AiLogClassifyRuleEntity entity, Long excludeId) {
        List<AiLogClassifyRuleEntity> conflicts = ruleMapper.selectEnabledByModuleAndReasonCode(
                entity.getAnalysisModule(), entity.getReasonCode());
        for (AiLogClassifyRuleEntity conflict : conflicts) {
            if (excludeId != null && excludeId.equals(conflict.getId())) {
                continue;
            }
            if (!entity.getCategory().equals(conflict.getCategory())) {
                throw new BusinessException("同一原因编码下根因分类不一致：原因编码="
                        + entity.getReasonCode() + "，已有规则分类=" + conflict.getCategory()
                        + "，当前规则分类=" + entity.getCategory());
            }
            if (!entity.getExpected().equals(conflict.getExpected())) {
                throw new BusinessException("同一原因编码下预期业务标识不一致：原因编码="
                        + entity.getReasonCode() + "，已有规则预期=" + conflict.getExpected()
                        + "，当前规则预期=" + entity.getExpected());
            }
            if (!entity.getAiRequired().equals(conflict.getAiRequired())) {
                throw new BusinessException("同一原因编码下AI分析标识不一致：原因编码="
                        + entity.getReasonCode() + "，已有规则AI=" + conflict.getAiRequired()
                        + "，当前规则AI=" + entity.getAiRequired());
            }
        }
    }

    private static RootCauseCategory requireCategory(String value) {
        try {
            ProblemType type = ProblemType.valueOf(value);
            if (!type.isManualAllowed()) {
                throw new BusinessException("根因分类不能使用" + value);
            }
            return RootCauseCategory.valueOf(type.name());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("根因分类不受支持：" + value, exception);
        }
    }

    private static RootCauseRuleType requireRuleType(String value) {
        try {
            return RootCauseRuleType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("规则类型不受支持：" + value, exception);
        }
    }

    private static void requireMatchTarget(String value) {
        try {
            RootCauseMatchTarget.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("匹配目标不受支持：" + value, exception);
        }
    }

    private static void validateRegex(String value) {
        try {
            Pattern.compile(value, REGEX_FLAGS);
        } catch (PatternSyntaxException exception) {
            throw new BusinessException("正则表达式格式不正确：" + exception.getDescription(),
                    exception);
        }
    }

    private static int requireLockVersion(Integer lockVersion) {
        if (lockVersion == null || lockVersion.intValue() < 0) {
            throw new BusinessException("乐观锁版本不能为空且不能小于0");
        }
        return lockVersion.intValue();
    }

    private static void requirePositiveRuleId(long ruleId) {
        if (ruleId <= 0L) {
            throw new BusinessException("规则ID必须大于0");
        }
    }

    private static String normalizeRequired(String value, String fieldName, int maximumLength) {
        String normalized = normalizeOptional(value, fieldName, maximumLength);
        if (normalized == null) {
            throw new BusinessException(fieldName + "不能为空");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, String fieldName, int maximumLength) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new BusinessException(fieldName + "不能超过" + maximumLength + "个字符");
        }
        return normalized;
    }

    private static String normalizeOptionalFilter(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String normalizeOptionalEnumFilter(String value, String fieldName) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (!normalized.equals(normalized.toUpperCase(Locale.ROOT))) {
            throw new BusinessException(fieldName + "必须使用大写枚举值");
        }
        return normalized;
    }

    private static BusinessException duplicateRuleException(DuplicateKeyException exception) {
        return new BusinessException("同一分析模块下已存在相同类型和匹配内容的规则", exception);
    }

    /** 按分类规则唯一键查询已有记录。 */
    private AiLogClassifyRuleEntity findByUniqueKey(AiLogClassifyRuleEntity entity,
            boolean lockForUpdate) {
        return ruleMapper.selectManagementByUniqueKey(entity.getAnalysisModule(),
                entity.getRuleType(), entity.getPattern(), lockForUpdate);
    }

    /** 构造包含已有记录ID和唯一键信息的新增冲突提示。 */
    private static BusinessException duplicateRuleCreateException(
            AiLogClassifyRuleEntity existing, AiLogClassifyRuleEntity requested,
            DuplicateKeyException cause) {
        String message;
        if (existing != null && existing.getId() != null) {
            message = "分类规则已存在：ID=" + existing.getId()
                    + "，分析模块=" + existing.getAnalysisModule()
                    + "，规则类型=" + existing.getRuleType()
                    + "，原因编码=" + existing.getReasonCode()
                    + "；请根据该ID查询详情后使用修改接口更新";
        } else {
            message = "分类规则已存在：分析模块=" + requested.getAnalysisModule()
                    + "，规则类型=" + requested.getRuleType()
                    + "，原因编码=" + requested.getReasonCode()
                    + "；请刷新列表并使用已有记录的修改接口更新";
        }
        return cause == null ? new BusinessException(message)
                : new BusinessException(message, cause);
    }

    private static ClassifyRuleResponse toResponse(AiLogClassifyRuleEntity entity) {
        ClassifyRuleResponse response = new ClassifyRuleResponse();
        response.setId(entity.getId());
        response.setAnalysisModule(entity.getAnalysisModule());
        response.setCategory(entity.getCategory());
        response.setRuleType(entity.getRuleType());
        response.setMatchTarget(entity.getMatchTarget());
        response.setPattern(entity.getPattern());
        response.setPriority(entity.getPriority());
        response.setEnabled(entity.getEnabled());
        response.setExpected(entity.getExpected());
        response.setAiRequired(entity.getAiRequired());
        response.setReasonCode(entity.getReasonCode());
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
