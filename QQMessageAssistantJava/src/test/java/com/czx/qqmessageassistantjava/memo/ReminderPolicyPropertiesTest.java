package com.czx.qqmessageassistantjava.memo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策略表里既有中文（分类名）、又有带冒号的值（{@code -1d@20:00}），YAML 写错会静默绑不上——
 * 这条测试就是拦这个的。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReminderPolicyPropertiesTest {

    @Autowired
    private ReminderPolicyProperties properties;

    @Test
    void policy_binds_chinese_category_names_from_yaml() {
        // 测试 profile 的列表里多了一个「测试桶」：它出现说明 YAML 整体覆盖了 Java 出厂值。
        // 曾经的写法是「中文 key: [标签]」的映射——Spring 属性名只认 ASCII，中文 key 被丢掉，
        // 只剩 "0"/"1" 两个假 key，改 YAML 永远不生效。
        assertThat(properties.policyMap()).containsKey("测试桶");
        assertThat(properties.policyMap())
                .containsKeys("学业", "财务", "社交", "健康", "日常", "其他");
        // 列表是整体覆盖而不是合并：6 个出厂桶 + 1 个测试桶
        assertThat(properties.getPolicy()).hasSize(7);
        assertThat(properties.policyMap().get("学业"))
                .containsExactly("-1d@20:00", "-1h");
    }

    @Test
    void limits_are_bound() {
        assertThat(properties.getMaxPerMemo()).isEqualTo(2);
        assertThat(properties.getDedupeMinutes()).isEqualTo(20);
    }

    /**
     * 生产 YAML 里 {@code -1d@20:00} 是带冒号的普通标量，写错引号或缩进就会静默变成 null，
     * 而 Java 侧的出厂默认值会让上面的测试照样变绿。这条直接读 application.yaml 兜底。
     */
    @Test
    void production_yaml_keeps_colon_values_intact() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        PropertySource<?> yaml = sources.get(0);

        assertThat(yaml.getProperty("app.reminder.policy[0].category")).isEqualTo("学业");
        assertThat(yaml.getProperty("app.reminder.policy[0].points[0]")).isEqualTo("-1d@20:00");
        assertThat(yaml.getProperty("app.reminder.policy[2].points[1]")).isEqualTo("-30m");
        assertThat(yaml.getProperty("app.reminder.max-per-memo")).isEqualTo(2);
    }

    @Test
    void bound_policy_drives_the_planner() {
        List<ReminderPlanner.Planned> plan = properties.toPlanner()
                .plan("财务", LocalDateTime.of(2026, 11, 5, 15, 0), 4,
                        LocalDateTime.of(2026, 11, 1, 10, 0));

        assertThat(plan).extracting(ReminderPlanner.Planned::label)
                .containsExactly("-1d@10:00", "@08:00");
    }
}
