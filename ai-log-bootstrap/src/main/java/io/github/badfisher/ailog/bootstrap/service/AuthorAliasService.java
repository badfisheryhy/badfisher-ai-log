package io.github.badfisher.ailog.bootstrap.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.badfisher.ailog.bootstrap.controller.response.ManagementOptionResponse;
import io.github.badfisher.ailog.persistence.config.entity.AiLogAuthorAliasEntity;
import io.github.badfisher.ailog.persistence.config.mapper.AiLogAuthorAliasMapper;
import lombok.Getter;

/** 作者别名只读字典服务。 */
@Service
public class AuthorAliasService {

    private final AiLogAuthorAliasMapper authorAliasMapper;

    public AuthorAliasService(AiLogAuthorAliasMapper authorAliasMapper) {
        this.authorAliasMapper = authorAliasMapper;
    }

    /** 每轮解析加载一次字典快照；调用方在该轮内复用，后续匹配不再查库。 */
    public AuthorAliasSnapshot loadSnapshot() {
        return AuthorAliasSnapshot.from(authorAliasMapper.selectList(null));
    }

    /** 查询真实用户对应的 Git Author 别名，去除首尾空白后供 SQL 筛选。 */
    public List<String> aliasesForUser(AuthorAliasSnapshot snapshot, Integer userId) {
        if (snapshot == null || userId == null) {
            return Collections.emptyList();
        }
        List<String> aliases = snapshot.aliasesByUserId.get(userId);
        return aliases == null ? Collections.<String>emptyList() : aliases;
    }

    /** 优先匹配启用别名，未命中则使用启用的兜底行；无可用兜底时返回 null。 */
    public AuthorUser resolve(AuthorAliasSnapshot snapshot, String blameAuthorName) {
        if (snapshot == null) {
            return null;
        }
        AuthorUser user = snapshot.userByNormalizedAlias.get(normalize(blameAuthorName));
        return user == null ? snapshot.defaultUser : user;
    }

    /** 将真实用户去重转换为管理端下拉选项。 */
    public List<ManagementOptionResponse> toOptions(AuthorAliasSnapshot snapshot) {
        if (snapshot == null || snapshot.userById.isEmpty()) {
            return Collections.emptyList();
        }
        List<ManagementOptionResponse> options = new ArrayList<>();
        int index = 1;
        for (AuthorUser user : snapshot.userById.values()) {
            options.add(new ManagementOptionResponse(String.valueOf(user.getUserId()),
                    user.getRealName(), Integer.valueOf(index * 10), Boolean.TRUE,
                    Boolean.FALSE));
            index++;
        }
        return options;
    }

    private static String normalize(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
    }

    /** 单次请求使用的不可变作者映射快照。 */
    @Getter
    public static final class AuthorAliasSnapshot {
        private final Map<Integer, AuthorUser> userById;
        private final Map<Integer, List<String>> aliasesByUserId;
        private final Map<String, AuthorUser> userByNormalizedAlias;
        private final AuthorUser defaultUser;

        private AuthorAliasSnapshot(Map<Integer, AuthorUser> userById,
                Map<Integer, List<String>> aliasesByUserId,
                Map<String, AuthorUser> userByNormalizedAlias, AuthorUser defaultUser) {
            this.defaultUser = defaultUser;
            this.userById = Collections.unmodifiableMap(userById);
            this.aliasesByUserId = Collections.unmodifiableMap(aliasesByUserId);
            this.userByNormalizedAlias = Collections.unmodifiableMap(userByNormalizedAlias);
        }

        private static AuthorAliasSnapshot from(List<AiLogAuthorAliasEntity> rows) {
            Map<Integer, AuthorUser> userById = new LinkedHashMap<>();
            Map<Integer, List<String>> aliasesByUserId = new LinkedHashMap<>();
            Map<String, AuthorUser> userByNormalizedAlias = new LinkedHashMap<>();
            AuthorUser defaultUser = null;
            if (rows != null) {
                for (AiLogAuthorAliasEntity row : rows) {
                    if (row == null || !Boolean.TRUE.equals(row.getEnabled()) || row.getUserId() == null
                            || normalize(row.getAliasName()).isEmpty()) {
                        continue;
                    }
                    AuthorUser user = userById.get(row.getUserId());
                    if (user == null) {
                        user = new AuthorUser(row.getUserId(), row.getRealName(), false);
                        userById.put(row.getUserId(), user);
                        aliasesByUserId.put(row.getUserId(), new ArrayList<String>());
                    }
                    if (Boolean.TRUE.equals(row.getDefaultUser())) {
                        if (defaultUser != null && !defaultUser.getUserId().equals(row.getUserId())) {
                            throw new IllegalStateException("启用的兜底配置不能指向不同用户");
                        }
                        defaultUser = new AuthorUser(user.getUserId(), user.getRealName(), true);
                    }
                    aliasesByUserId.get(row.getUserId()).add(row.getAliasName().trim());
                    AuthorUser previous = userByNormalizedAlias.putIfAbsent(normalize(row.getAliasName()), user);
                    if (previous != null && !previous.getUserId().equals(user.getUserId())) {
                        throw new IllegalStateException("作者别名归一化后对应多个用户：" + row.getAliasName());
                    }
                }
            }
            Map<Integer, List<String>> immutableAliases = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<String>> entry : aliasesByUserId.entrySet()) {
                immutableAliases.put(entry.getKey(),
                        Collections.unmodifiableList(new ArrayList<String>(entry.getValue())));
            }
            return new AuthorAliasSnapshot(userById, immutableAliases,
                    userByNormalizedAlias, defaultUser);
        }
    }

    /** 映射后的真实作者用户。 */
    @Getter
    public static final class AuthorUser {
        private final Integer userId;
        private final String realName;
        /** true 表示默认兜底，false 表示显式别名命中；不代表已经人工指派。 */
        private final boolean defaultFallback;

        private AuthorUser(Integer userId, String realName, boolean defaultFallback) {
            this.userId = userId;
            this.realName = realName;
            this.defaultFallback = defaultFallback;
        }
    }
}
