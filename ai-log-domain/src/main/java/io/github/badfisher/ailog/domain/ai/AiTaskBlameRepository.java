package io.github.badfisher.ailog.domain.ai;

import java.time.LocalDateTime;

/** AI Item 源码位置读取及 Author 归属写入端口。 */
public interface AiTaskBlameRepository {
    /** 返回本次 Item 固定代表 Event 内成对有效的 BUSINESS 或 LOGGER 位置；不存在时返回 null。 */
    AiTaskSourceLocation findSourceLocation(long itemId, long issueGroupId);

    /** 仅对当前有效领取写入新归属；未命中或令牌过期返回 false。 */
    boolean updateBlame(AiTaskClaim claim, Long operationId, String operationExecutionToken,
            GitBlameResult result, LocalDateTime now);
}
