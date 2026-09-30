package io.github.badfisher.ailog.bootstrap.controller.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 相同筛选条件下本人负责的问题统计，审核状态不充当处理状态。 */
@Data
@Schema(name = "PersonalWorkbenchSummary")
public class PersonalWorkbenchSummary {
    /** 本人负责、未解决、未完成且未忽略的问题数。 */
    private long myOpenCount;
    /** 本人负责且审核状态为 PENDING 的问题数。 */
    private long pendingReviewCount;
    /** 本人负责且处理中的问题数。 */
    private long processingCount;
    /** 本人负责且已解决的问题数。 */
    private long resolvedCount;
    /** 本人负责且已验收完成的问题数。 */
    private long completedCount;
    /** 本人负责且已忽略的问题数。 */
    private long ignoredCount;
}
