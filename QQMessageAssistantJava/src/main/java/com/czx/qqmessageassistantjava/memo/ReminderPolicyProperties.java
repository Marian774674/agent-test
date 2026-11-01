package com.czx.qqmessageassistantjava.memo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.reminder")
public class ReminderPolicyProperties {

    /** 一条备忘最多推几次。 */
    private int maxPerMemo = 2;

    /** 两个提醒点相差小于这个分钟数就合并成一条。 */
    private int dedupeMinutes = 20;

    /** category → 标签列表，按声明顺序取用。 */
    private Map<String, List<String>> policy = defaultPolicy();

    public ReminderPlanner toPlanner() {
        return new ReminderPlanner(policy, maxPerMemo, dedupeMinutes);
    }

    /**
     * 与 {@code application.yaml} 保持一致的出厂值：YAML 里没配（比如精简的测试 profile）
     * 时也能排出合理的提醒。
     */
    private static Map<String, List<String>> defaultPolicy() {
        Map<String, List<String>> defaults = new LinkedHashMap<>();
        defaults.put("学业", List.of("-1d@20:00", "-1h"));
        defaults.put("财务", List.of("-1d@10:00", "@08:00"));
        defaults.put("社交", List.of("-2h", "-30m"));
        defaults.put("健康", List.of("-30m"));
        defaults.put("日常", List.of("-30m"));
        defaults.put("其他", List.of("-1h"));
        return defaults;
    }
}
