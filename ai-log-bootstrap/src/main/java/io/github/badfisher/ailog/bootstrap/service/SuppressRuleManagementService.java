package io.github.badfisher.ailog.bootstrap.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.badfisher.ailog.bootstrap.controller.request.EnabledStatusRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.SuppressRuleRequest;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogSuppressRuleEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogSuppressRuleMapper;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 只管理关键词规则的增删改查，不参与事件匹配。 */
@Service
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class SuppressRuleManagementService {

    private final AiLogSuppressRuleMapper mapper;
    private final ManagementActorProvider actors;

    public SuppressRuleManagementService(AiLogSuppressRuleMapper ruleMapper,
            ManagementActorProvider actorProvider) {
        mapper = ruleMapper;
        actors = actorProvider;
    }

    @Transactional(readOnly = true)
    public List<AiLogSuppressRuleEntity> list(String systemCode, String moduleCode,
            Boolean enabled) {
        LambdaQueryWrapper<AiLogSuppressRuleEntity> query = Wrappers.lambdaQuery();
        if (hasText(systemCode)) {
            query.eq(AiLogSuppressRuleEntity::getSystemCode, systemCode.trim());
        }
        if (hasText(moduleCode)) {
            query.eq(AiLogSuppressRuleEntity::getModuleCode, moduleCode.trim());
        }
        if (enabled != null) {
            query.eq(AiLogSuppressRuleEntity::getEnabled, enabled);
        }
        query.orderByAsc(AiLogSuppressRuleEntity::getSystemCode)
                .orderByAsc(AiLogSuppressRuleEntity::getModuleCode)
                .orderByAsc(AiLogSuppressRuleEntity::getId);
        return mapper.selectList(query);
    }

    @Transactional(readOnly = true)
    public AiLogSuppressRuleEntity detail(long ruleId) {
        requireId(ruleId);
        AiLogSuppressRuleEntity rule = mapper.selectById(Long.valueOf(ruleId));
        if (rule == null) {
            throw new BusinessException("未找到过滤规则，规则ID：" + ruleId);
        }
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public AiLogSuppressRuleEntity create(SuppressRuleRequest request) {
        AiLogSuppressRuleEntity rule = validatedRule(request);
        rejectDuplicate(rule, null);
        ManagementActor actor = actors.requireActorIdentity();
        LocalDateTime now = LocalDateTime.now();
        rule.setCreateUserId(actor.getUserId());
        rule.setCreateUserName(actor.getUsername());
        rule.setCreateTime(now);
        rule.setUpdateUserId(actor.getUserId());
        rule.setUpdateUserName(actor.getUsername());
        rule.setUpdateTime(now);
        rule.setLockVersion(Integer.valueOf(0));
        try {
            if (mapper.insert(rule) != 1 || rule.getId() == null) {
                throw new BusinessException("过滤规则创建失败，请稍后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateKeyword(exception);
        }
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public AiLogSuppressRuleEntity update(SuppressRuleRequest request) {
        long ruleId = requireId(request == null ? null : request.getId());
        int version = requireVersion(request.getLockVersion());
        detail(ruleId);
        AiLogSuppressRuleEntity rule = validatedRule(request);
        rejectDuplicate(rule, Long.valueOf(ruleId));
        ManagementActor actor = actors.requireActorIdentity();
        try {
            int updated = mapper.update(null, Wrappers.<AiLogSuppressRuleEntity>lambdaUpdate()
                    .eq(AiLogSuppressRuleEntity::getId, Long.valueOf(ruleId))
                    .eq(AiLogSuppressRuleEntity::getLockVersion, Integer.valueOf(version))
                    .set(AiLogSuppressRuleEntity::getSystemCode, rule.getSystemCode())
                    .set(AiLogSuppressRuleEntity::getModuleCode, rule.getModuleCode())
                    .set(AiLogSuppressRuleEntity::getKeyword, rule.getKeyword())
                    .set(AiLogSuppressRuleEntity::getEnabled, rule.getEnabled())
                    .set(AiLogSuppressRuleEntity::getRemark, rule.getRemark())
                    .set(AiLogSuppressRuleEntity::getUpdateUserId, actor.getUserId())
                    .set(AiLogSuppressRuleEntity::getUpdateUserName, actor.getUsername())
                    .set(AiLogSuppressRuleEntity::getUpdateTime, LocalDateTime.now())
                    .set(AiLogSuppressRuleEntity::getLockVersion, Integer.valueOf(version + 1)));
            if (updated != 1) {
                throwUpdateRejected(ruleId, version);
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateKeyword(exception);
        }
        return detail(ruleId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiLogSuppressRuleEntity changeEnabled(long ruleId, EnabledStatusRequest request) {
        requireId(ruleId);
        if (request == null || request.getEnabled() == null) {
            throw new BusinessException("启用状态不能为空");
        }
        int version = requireVersion(request.getLockVersion());
        ManagementActor actor = actors.requireActorIdentity();
        int updated = mapper.update(null, Wrappers.<AiLogSuppressRuleEntity>lambdaUpdate()
                .eq(AiLogSuppressRuleEntity::getId, Long.valueOf(ruleId))
                .eq(AiLogSuppressRuleEntity::getLockVersion, Integer.valueOf(version))
                .set(AiLogSuppressRuleEntity::getEnabled, request.getEnabled())
                .set(AiLogSuppressRuleEntity::getUpdateUserId, actor.getUserId())
                .set(AiLogSuppressRuleEntity::getUpdateUserName, actor.getUsername())
                .set(AiLogSuppressRuleEntity::getUpdateTime, LocalDateTime.now())
                .set(AiLogSuppressRuleEntity::getLockVersion, Integer.valueOf(version + 1)));
        if (updated != 1) {
            throwUpdateRejected(ruleId, version);
        }
        return detail(ruleId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long ruleId, Integer lockVersion) {
        EnabledStatusRequest request = new EnabledStatusRequest();
        request.setEnabled(Boolean.FALSE);
        request.setLockVersion(lockVersion);
        changeEnabled(ruleId, request);
    }

    private void throwUpdateRejected(long ruleId, int version) {
        AiLogSuppressRuleEntity current = detail(ruleId);
        throw new BusinessException("过滤规则已被其他用户更新，请刷新后重试；提交版本："
                + version + "，当前版本：" + current.getLockVersion());
    }

    private static AiLogSuppressRuleEntity validatedRule(SuppressRuleRequest request) {
        if (request == null) {
            throw new BusinessException("过滤规则请求不能为空");
        }
        if (!hasText(request.getSystemCode()) || !hasText(request.getModuleCode())
                || !hasText(request.getKeyword())) {
            throw new BusinessException("系统编码、模块编码和过滤关键词不能为空");
        }
        String systemCode = request.getSystemCode().trim();
        String moduleCode = request.getModuleCode().trim();
        if (systemCode.length() > 64 || moduleCode.length() > 128) {
            throw new BusinessException("系统编码或模块编码长度不合法");
        }
        String keyword = request.getKeyword().trim().toLowerCase(Locale.ROOT);
        if (keyword.length() > 512) {
            throw new BusinessException("过滤关键词不能超过512个字符");
        }
        if (request.getEnabled() == null) {
            throw new BusinessException("启用状态不能为空");
        }
        String remark = hasText(request.getRemark()) ? request.getRemark().trim() : null;
        if (remark != null && remark.length() > 512) {
            throw new BusinessException("备注不能超过512个字符");
        }
        AiLogSuppressRuleEntity rule = new AiLogSuppressRuleEntity();
        rule.setSystemCode(systemCode);
        rule.setModuleCode(moduleCode);
        rule.setKeyword(keyword);
        rule.setEnabled(request.getEnabled());
        rule.setRemark(remark);
        return rule;
    }

    private static long requireId(Long id) {
        if (id == null || id.longValue() <= 0L) {
            throw new BusinessException("规则ID必须大于0");
        }
        return id.longValue();
    }

    private static int requireVersion(Integer version) {
        if (version == null || version.intValue() < 0) {
            throw new BusinessException("乐观锁版本不合法");
        }
        return version.intValue();
    }

    private void rejectDuplicate(AiLogSuppressRuleEntity rule, Long excludedId) {
        LambdaQueryWrapper<AiLogSuppressRuleEntity> query = Wrappers.lambdaQuery();
        query.eq(AiLogSuppressRuleEntity::getSystemCode, rule.getSystemCode())
                .eq(AiLogSuppressRuleEntity::getModuleCode, rule.getModuleCode())
                .eq(AiLogSuppressRuleEntity::getKeyword, rule.getKeyword());
        if (excludedId != null) {
            query.ne(AiLogSuppressRuleEntity::getId, excludedId);
        }
        if (mapper.selectCount(query) > 0) {
            throw new BusinessException("同一系统和模块下已存在相同关键词的过滤规则");
        }
    }

    private static BusinessException duplicateKeyword(DuplicateKeyException exception) {
        return new BusinessException("同一系统和模块下已存在相同关键词的过滤规则", exception);
    }
}
