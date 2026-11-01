package com.czx.qqmessageassistantjava.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 进入首页时「要不要现在整理一次」的结果。 */
@Data
@Schema(description = "首页整理触发结果")
public class DigestRefreshVO {

    @Schema(description = "整理总开关是否打开")
    private boolean enabled;
    @Schema(description = "这次是否真的跑了整理（消息够多或隔得够久）")
    private boolean refreshed;
    @Schema(description = "距上次整理收到多少条消息")
    private long messageCount;
    @Schema(description = "距上次整理多少分钟")
    private long minutesSince;
    @Schema(description = "本次写入的整理条数")
    private int saved;
    @Schema(description = "本次窗口的起点（上次整理的时间）")
    private LocalDateTime windowStart;
}
