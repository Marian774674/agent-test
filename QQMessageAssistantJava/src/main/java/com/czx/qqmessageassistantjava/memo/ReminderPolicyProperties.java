package com.czx.qqmessageassistantjava.memo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提醒策略配置（{@code app.reminder.policy} 等）。
 *
 * <p>按 <b>事情类型</b>（{@code memo.category}）决定提前多久，不再看 importance：
 * 缴费和买牛奶的紧急程度不是同一回事，一张按重要度的表必然对其中一个是错的。
 *
 * <p>标签语法见 {@link ReminderPlanner}。改这里之后重启即可生效，不用改代码；
 * 写错的标签不会让备忘建不出来，只会跳过并在启动后的首次排程里 WARN。
 *
 * <p>策略写成 <b>列表</b>而不是 {@code Map<String, List<String>>}：Spring 属性名只认
 * ASCII，{@code app.reminder.policy.学业} 这种中文 key 在绑定阶段会被丢掉（剩下的下标
 * 还会变成 "0"/"1" 这种假 key），于是 YAML 改了也不生效、悄悄用回出厂值。把分类名放进
 * {@code category} 字段当值，才能真的绑上。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.reminder")
public class ReminderPolicyProperties {

    /** 一条备忘最多推几次。 */
    private int maxPerMemo = 2;

    /** 两个提醒点相差小于这个分钟数就合并成一条。 */
    private int dedupeMinutes = 20;

    /** 按声明顺序取用；同一分类写多次时以第一条为准。 */
    private List<Bucket> policy = defaultPolicy();

    public ReminderPlanner toPlanner() {
        return new ReminderPlanner(policyMap(), maxPerMemo, dedupeMinutes);
    }

    /** 列表 → 查找表；顺带过滤掉没写 category 的空条目。 */
    public Map<String, List<String>> policyMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (Bucket bucket : policy) {
            if (bucket == null || bucket.getCategory() == null || bucket.getCategory().isBlank()) {
                continue;
            }
            List<String> points = bucket.getPoints() == null ? List.of() : bucket.getPoints();
            map.putIfAbsent(bucket.getCategory(), points);
        }
        return map;
    }

    @Data
    public static class Bucket {
        /** 对应 {@code memo.category}，如「学业」。 */
        private String category;
        /** 标签列表，如 {@code ['-1d@20:00', '-1h']}。 */
        private List<String> points = new ArrayList<>();
    }

    /**
     * 与 {@code application.yaml} 保持一致的出厂值：YAML 里没配（比如精简的测试 profile）
     * 时也能排出合理的提醒。
     */
    private static List<Bucket> defaultPolicy() {
        List<Bucket> defaults = new ArrayList<>();
        defaults.add(bucket("学业", "-1d@20:00", "-1h"));
        defaults.add(bucket("财务", "-1d@10:00", "@08:00"));
        defaults.add(bucket("社交", "-2h", "-30m"));
        defaults.add(bucket("健康", "-30m"));
        defaults.add(bucket("日常", "-30m"));
        defaults.add(bucket("其他", "-1h"));
        return defaults;
    }

    private static Bucket bucket(String category, String... points) {
        Bucket bucket = new Bucket();
        bucket.setCategory(category);
        bucket.setPoints(new ArrayList<>(List.of(points)));
        return bucket;
    }
}
