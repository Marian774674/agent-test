package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条备忘的某个提醒档位。
 *
 * <p>{@code offsetText} 由后端翻译，前端直接显示——旧的写法把「24h → 提前 24 小时」的
 * 映射写在 Vue 里，档位一改前端就得跟着改，现在只有一处。
 */
@Data
public class MemoReminderVO {

    private Long id;

    @Schema(description = "档位标签，如 -1d@20:00 / -1h / 立即")
    private String offsetLabel;

    @Schema(description = "中文说明，如「前一天 20:00」")
    private String offsetText;

    private LocalDateTime remindAt;

    @Schema(description = "PENDING | SENT | CANCELLED")
    private String status;

    private LocalDateTime sentAt;
}
