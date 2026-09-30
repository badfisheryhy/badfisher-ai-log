package io.github.badfisher.ailog.bootstrap.controller.request;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 日期范围内出现过的问题及其当前治理状态，不表示历史治理快照。 */
@Data
@Schema(name = "DashboardQueryRequest")
public class DashboardQueryRequest {
    /** 日志业务日期起点，包含当天。 */
    @NotNull
    private LocalDate startDate;
    /** 日志业务日期终点，包含当天；不从任务执行时间推断。 */
    @NotNull
    private LocalDate endDate;
    /** 环境编码。 */
    @Size(max = 32)
    private String environment;
    /** 系统编码。 */
    @Size(max = 64)
    private String systemCode;
    /** 模块编码。 */
    @Size(max = 128)
    private String moduleCode;

    /** 限制补齐日期后的图表规模，单次最多查询 366 个业务日。 */
    @AssertTrue(message = "日期范围须按先后顺序且不超过366天")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null
                || (!startDate.isAfter(endDate) && ChronoUnit.DAYS.between(startDate, endDate) < 366);
    }
}
