package com.czx.qqmessageassistantjava.memo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把「一条备忘」算成「该在哪些时刻提醒」，不碰数据库、不依赖 Spring。
 *
 * <p>策略按 <b>category</b>（学业 / 财务 / 社交 …）而不是 importance 选，
 * 每个档位是一个标签，语法三种（见 {@code design/api_contract.md} §5.1）：
 *
 * <ul>
 *   <li>{@code @HH:mm} —— 锚点当天的这个时刻</li>
 *   <li>{@code ±Nd@HH:mm} —— 锚点日期 ±N 天，再取这个时刻（{@code -1d@20:00} = 前一天 20:00）</li>
 *   <li>{@code ±N[dhm]} —— 相对锚点提前的天/小时/分钟</li>
 * </ul>
 *
 * <p><b>锚点归一化</b>：LLM 经常只给日期，{@code FlexibleLocalDateTimeDeserializer} 会解析成
 * 当天 00:00。若按 00:00 直接减 24h/2h/30min，提醒全落在半夜——所以时刻部分为 00:00 时，
 * 锚点取当天 09:00。
 *
 * <p>生成规则：先丢过去的点，再合并靠得过近的点，再截断到 {@code maxPerMemo}；一条都没剩下
 * 但到期时间还在未来时，补一条「立即」，避免临到点才记的事全程没有提醒。
 */
public class ReminderPlanner {

    private static final Logger log = LoggerFactory.getLogger(ReminderPlanner.class);

    /** 兜底档位：临近创建、其余档位都已过去时使用。 */
    public static final String IMMEDIATE = "立即";

    /** category 为空或不认识时用的键。 */
    public static final String DEFAULT_BUCKET = "其他";

    private static final LocalTime ANCHOR_TIME_FOR_DATE_ONLY = LocalTime.of(9, 0);

    private static final Pattern FIXED_TIME = Pattern.compile("^([+-]?\\d+d)?@(\\d{1,2}):(\\d{2})$");
    private static final Pattern RELATIVE = Pattern.compile("^([+-])(\\d+)([dhm])$");

    private final Map<String, List<String>> policy;
    private final int maxPerMemo;
    private final int dedupeMinutes;

    public ReminderPlanner(Map<String, List<String>> policy, int maxPerMemo, int dedupeMinutes) {
        this.policy = policy == null ? Map.of() : policy;
        this.maxPerMemo = Math.max(1, maxPerMemo);
        this.dedupeMinutes = Math.max(0, dedupeMinutes);
    }

    /** 一个提醒档位：标签与它对应的推送时刻。 */
    public record Planned(String label, LocalDateTime remindAt) {
    }

    /**
     * @param category   memo.category，null 或未知值落到 {@code 其他}
     * @param dueTime    到期时间；null 返回空
     * @param importance 1 = 纯信息只存档，不排提醒
     * @param now        计算基准时间（测试可注入）
     */
    public List<Planned> plan(String category, LocalDateTime dueTime, Integer importance, LocalDateTime now) {
        if (dueTime == null || now == null) {
            return List.of();
        }
        if (importance != null && importance <= 1) {
            return List.of();
        }

        LocalDateTime anchor = anchorOf(dueTime);
        List<Planned> planned = new ArrayList<>();
        for (String label : labelsFor(category)) {
            LocalDateTime remindAt = compute(anchor, label);
            if (remindAt == null) {
                continue;
            }
            if (!remindAt.isAfter(now)) {
                continue;                       // 过去的档位跳过，避免集中补发
            }
            if (isTooCloseToKept(planned, remindAt)) {
                continue;
            }
            planned.add(new Planned(label, remindAt));
            if (planned.size() >= maxPerMemo) {
                break;
            }
        }
        if (!planned.isEmpty()) {
            return planned;
        }
        Planned immediate = immediateFor(dueTime, now);
        return immediate == null ? List.of() : List.of(immediate);
    }

    /** 时刻部分为 00:00（只给了日期）时锚点取当天 09:00，其余原样。 */
    static LocalDateTime anchorOf(LocalDateTime dueTime) {
        if (dueTime.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return dueTime.toLocalDate().atTime(ANCHOR_TIME_FOR_DATE_ONLY);
        }
        return dueTime;
    }

    private List<String> labelsFor(String category) {
        String key = category == null || category.isBlank() ? DEFAULT_BUCKET : category;
        List<String> labels = policy.get(key);
        if (labels == null) {
            labels = policy.get(DEFAULT_BUCKET);
        }
        return labels == null ? List.of() : labels;
    }

    private boolean isTooCloseToKept(List<Planned> planned, LocalDateTime remindAt) {
        if (dedupeMinutes <= 0) {
            return false;
        }
        return planned.stream().anyMatch(kept ->
                Math.abs(Duration.between(kept.remindAt(), remindAt).toMinutes()) < dedupeMinutes);
    }

    /** 标签算不出时间（配置写错）时返回 null，不抛异常——一条写错不该让备忘建不出来。 */
    private LocalDateTime compute(LocalDateTime anchor, String label) {
        if (label == null) {
            return null;
        }
        Matcher fixed = FIXED_TIME.matcher(label.trim());
        if (fixed.matches()) {
            int hour = Integer.parseInt(fixed.group(2));
            int minute = Integer.parseInt(fixed.group(3));
            if (hour > 23 || minute > 59) {
                log.warn("提醒标签时刻非法，已跳过：{}", label);
                return null;
            }
            int days = 0;
            if (fixed.group(1) != null) {
                days = Integer.parseInt(fixed.group(1).substring(0, fixed.group(1).length() - 1));
            }
            return anchor.toLocalDate().plusDays(days).atTime(hour, minute);
        }

        Matcher relative = RELATIVE.matcher(label.trim());
        if (relative.matches()) {
            long amount = Long.parseLong(relative.group(2));
            long minutes = switch (relative.group(3)) {
                case "d" -> amount * 24 * 60;
                case "h" -> amount * 60;
                default -> amount;
            };
            return "-".equals(relative.group(1))
                    ? anchor.minusMinutes(minutes)
                    : anchor.plusMinutes(minutes);
        }

        log.warn("无法识别的提醒标签，已跳过：{}（可选写法：-1d@20:00 / @08:00 / -2h / -30m）", label);
        return null;
    }

    private static Planned immediateFor(LocalDateTime dueTime, LocalDateTime now) {
        if (!dueTime.isAfter(now)) {
            return null;
        }
        LocalDateTime remindAt = now.plusMinutes(1);
        if (remindAt.isAfter(dueTime.minusMinutes(1))) {
            remindAt = dueTime.minusMinutes(1);
        }
        if (!remindAt.isAfter(now)) {
            remindAt = now.plusSeconds(1);
        }
        if (!remindAt.isAfter(now) || remindAt.isAfter(dueTime)) {
            return null;
        }
        return new Planned(IMMEDIATE, remindAt);
    }

    /** 标签 → 中文说明，给前端直接显示，避免前后端各维护一张映射表。 */
    public static String describe(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        if (IMMEDIATE.equals(label)) {
            return "马上";
        }

        Matcher fixed = FIXED_TIME.matcher(label);
        if (fixed.matches()) {
            String clock = String.format("%02d:%s", Integer.parseInt(fixed.group(2)), fixed.group(3));
            if (fixed.group(1) == null) {
                return "当天 " + clock;
            }
            int days = Integer.parseInt(fixed.group(1).substring(0, fixed.group(1).length() - 1));
            return dayPrefix(days) + " " + clock;
        }

        Matcher relative = RELATIVE.matcher(label);
        if (relative.matches()) {
            boolean later = "+".equals(relative.group(1));
            long amount = Long.parseLong(relative.group(2));
            String unit = switch (relative.group(3)) {
                case "d" -> "天";
                case "h" -> "小时";
                default -> "分钟";
            };
            String prefix = later ? "延后 " : "提前 ";
            if ("分钟".equals(unit)) {
                return prefix + durationText(amount);
            }
            return prefix + amount + " " + unit;
        }

        return label;   // 旧库里的 24h / 2h / 当天 之类原样透出
    }

    private static String dayPrefix(int days) {
        if (days == 0) {
            return "当天";
        }
        if (days == -1) {
            return "前一天";
        }
        if (days < 0) {
            return "提前 " + (-days) + " 天";
        }
        return days == 1 ? "次日" : "后 " + days + " 天";
    }

    private static String durationText(long minutes) {
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分钟";
    }

    /** 仅供日志/调试：把配置表渲染成一行。 */
    @Override
    public String toString() {
        return "ReminderPlanner{policy=" + policy + ", maxPerMemo=" + maxPerMemo
                + ", dedupeMinutes=" + dedupeMinutes + '}';
    }
}
