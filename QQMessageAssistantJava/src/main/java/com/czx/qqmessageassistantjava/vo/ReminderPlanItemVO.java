package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编辑器里的「将在这几个时刻提醒你」预览。
 *
 * <p>由后端用与真实排程相同的 {@code ReminderPlanner} 算出，前端不再自己维护一份
 * 「重要度 → 提醒档位」的映射表，避免预览和实际排出来的对不上。
 */
@Data
public class ReminderPlanItemVO {

    @Schema(description = "档位标签，如 -1d@20:00 / -1h / 立即")
    private String label;

    @Schema(description = "中文说明，如「前一天 20:00」")
    private String text;

    private LocalDateTime remindAt;
}
