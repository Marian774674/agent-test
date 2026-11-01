package com.czx.qqmessageassistantjava.memo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p><b>到期提醒</b>：只要到期时间还没过，每条备忘都会额外排一条「截止」——类型表管的是
 * 「提前多久」，管不了「到点必须响一次」。它固定占用一个名额（{@code maxPerMemo} 不够时
 * 优先砍离到期最远的提前档），纯日期备忘也落在锚点 09:00 而不是半夜 00:00。
 *
 * <p>生成规则：先丢过去的点，再合并靠得过近的点，再截断到 {@code maxPerMemo}，最后补「截止」。
 */
public class ReminderPlanner {

    private static final Logger log = LoggerFactory.getLogger(ReminderPlanner.class);

    /** 旧数据里的兜底档位，现在不再生成，只在 {@link #describe} 里保留中文翻译。 */
    public static final String IMMEDIATE = "立即";

    /** 到期那一条：除纯存档外，每条备忘都会有。 */
    public static final String DUE = "截止";

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
        boolean dueInFuture = dueTime.isAfter(now);

        List<Planned> planned = new ArrayList<>();
        for (String label : labelsFor(category)) {
            LocalDateTime remindAt = compute(anchor, label);
            if (remindAt == null || !remindAt.isAfter(now)) {
                continue;                       // 写错的标签跳过，过去的档位跳过，避免集中补发
            }
            if (isTooCloseToKept(planned, remindAt)) {
                continue;
            }
            planned.add(new Planned(label, remindAt));
        }

        // 上限要给「截止」留一个位置；超了先砍离到期最远的提前档
        int budget = dueInFuture ? Math.max(0, maxPerMemo - 1) : maxPerMemo;
        if (planned.size() > budget) {
            planned.sort(Comparator.comparing(Planned::remindAt));
            planned = new ArrayList<>(planned.subList(planned.size() - budget, planned.size()));
        }

        if (dueInFuture) {
            // 落在锚点：纯日期备忘的「到点」是 09:00，不是 00:00
            planned.removeIf(kept -> isTooClose(kept.remindAt(), anchor));
            planned.add(new Planned(DUE, anchor));
        }

        planned.sort(Comparator.comparing(Planned::remindAt));
        return planned;
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
        return planned.stream().anyMatch(kept -> isTooClose(kept.remindAt(), remindAt));
    }

    private boolean isTooClose(LocalDateTime a, LocalDateTime b) {
        if (dedupeMinutes <= 0) {
            return false;
        }
        return Math.abs(Duration.between(a, b).toMinutes()) < dedupeMinutes;
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

    /** 标签 → 中文说明，给前端直接显示，避免前后端各维护一张映射表。 */
    public static String describe(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        if (IMMEDIATE.equals(label)) {
            return "马上";
        }
        if (DUE.equals(label)) {
            return "到期时";
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
