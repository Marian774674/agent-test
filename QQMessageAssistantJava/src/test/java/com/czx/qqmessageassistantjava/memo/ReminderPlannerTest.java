package com.czx.qqmessageassistantjava.memo;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提醒计划是纯函数：给定类型 / 到期时间 / 当前时间，算出该在哪几个时刻推。
 *
 * <p>规则见 design/api_contract.md §5.1。这里钉住三件最容易被改坏的事：
 * 纯日期不被算成半夜、临近创建仍有提醒、同一条备忘的条数有上限。
 */
class ReminderPlannerTest {

    private static final Map<String, List<String>> POLICY = Map.of(
            "学业", List.of("-1d@20:00", "-1h"),
            "财务", List.of("-1d@10:00", "@08:00"),
            "社交", List.of("-2h", "-30m"),
            "其他", List.of("-1h")
    );

    private static ReminderPlanner planner() {
        return new ReminderPlanner(POLICY, 2, 20);
    }

    private static LocalDateTime at(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute);
    }

    @Test
    void category_decides_the_plan_not_importance() {
        // 同一天、同一重要度，只有类型不同 → 提醒点完全不同
        var now = at(11, 1, 10, 0);
        var due = at(11, 2, 15, 0);

        assertThat(planner().plan("学业", due, 3, now))
                .extracting(ReminderPlanner.Planned::label, ReminderPlanner.Planned::remindAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("-1d@20:00", at(11, 1, 20, 0)),
                        org.assertj.core.groups.Tuple.tuple("-1h", at(11, 2, 14, 0)));

        assertThat(planner().plan("社交", due, 3, now))
                .extracting(ReminderPlanner.Planned::label, ReminderPlanner.Planned::remindAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("-2h", at(11, 2, 13, 0)),
                        org.assertj.core.groups.Tuple.tuple("-30m", at(11, 2, 14, 30)));
    }

    @Test
    void date_only_due_time_anchors_at_nine_am_and_never_lands_at_night() {
        var now = at(11, 1, 10, 0);
        var due = at(11, 5, 0, 0);           // LLM 只给了日期 → 解析成 00:00

        var plan = planner().plan("学业", due, 5, now);

        assertThat(plan).extracting(ReminderPlanner.Planned::remindAt)
                .containsExactly(at(11, 4, 20, 0), at(11, 5, 8, 0));
        // 旧实现会把 24h/2h/30min 算成 11-04 00:00 / 11-04 22:00 / 11-04 23:30
        assertThat(plan).allSatisfy(p -> assertThat(p.remindAt().getHour()).isGreaterThanOrEqualTo(7));
    }

    @Test
    void past_points_are_skipped() {
        // 已经过了「前一天 20:00」，只剩临近的一档
        var plan = planner().plan("学业", at(11, 5, 15, 0), 3, at(11, 4, 21, 0));

        assertThat(plan).extracting(ReminderPlanner.Planned::label).containsExactly("-1h");
        assertThat(plan.get(0).remindAt()).isEqualTo(at(11, 5, 14, 0));
    }

    @Test
    void all_points_past_still_yields_one_immediate_reminder() {
        // 14:50 才记「3 点开会」：旧实现一条提醒都不排
        var now = at(11, 5, 14, 50);
        var plan = planner().plan("社交", at(11, 5, 15, 0), 3, now);

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).label()).isEqualTo(ReminderPlanner.IMMEDIATE);
        assertThat(plan.get(0).remindAt()).isAfter(now).isBefore(at(11, 5, 15, 0));
    }

    @Test
    void expired_due_time_plans_nothing() {
        assertThat(planner().plan("学业", at(11, 4, 15, 0), 3, at(11, 5, 10, 0))).isEmpty();
    }

    @Test
    void near_identical_points_are_merged() {
        var planner = new ReminderPlanner(Map.of("其他", List.of("@20:00", "-1h")), 2, 20);

        var plan = planner.plan("其他", at(11, 5, 21, 0), 3, at(11, 5, 10, 0));

        assertThat(plan).extracting(ReminderPlanner.Planned::remindAt).containsExactly(at(11, 5, 20, 0));
        assertThat(plan).hasSize(1);
    }

    @Test
    void plan_is_truncated_to_max_per_memo() {
        var planner = new ReminderPlanner(
                Map.of("其他", List.of("-1d@09:00", "-2h", "-30m")), 2, 20);

        var plan = planner.plan("其他", at(11, 5, 15, 0), 3, at(11, 1, 10, 0));

        assertThat(plan).extracting(ReminderPlanner.Planned::label).containsExactly("-1d@09:00", "-2h");
    }

    @Test
    void archived_importance_plans_nothing() {
        assertThat(planner().plan("学业", at(11, 5, 15, 0), 1, at(11, 1, 10, 0))).isEmpty();
    }

    @Test
    void missing_due_time_plans_nothing() {
        assertThat(planner().plan("学业", null, 5, at(11, 1, 10, 0))).isEmpty();
    }

    @Test
    void unknown_category_falls_back_to_default_bucket() {
        var plan = planner().plan("不存在的类型", at(11, 5, 15, 0), 3, at(11, 1, 10, 0));

        assertThat(plan).extracting(ReminderPlanner.Planned::label).containsExactly("-1h");
    }

    @Test
    void unknown_token_is_skipped_without_losing_the_rest() {
        var planner = new ReminderPlanner(Map.of("其他", List.of("abc", "-30m")), 2, 20);

        var plan = planner.plan("其他", at(11, 5, 15, 0), 3, at(11, 1, 10, 0));

        assertThat(plan).extracting(ReminderPlanner.Planned::label).containsExactly("-30m");
    }

    @Test
    void labels_are_described_in_chinese() {
        assertThat(ReminderPlanner.describe("-1d@20:00")).isEqualTo("前一天 20:00");
        assertThat(ReminderPlanner.describe("-2d@09:00")).isEqualTo("提前 2 天 09:00");
        assertThat(ReminderPlanner.describe("@08:00")).isEqualTo("当天 08:00");
        assertThat(ReminderPlanner.describe("-2h")).isEqualTo("提前 2 小时");
        assertThat(ReminderPlanner.describe("-90m")).isEqualTo("提前 1 小时 30 分钟");
        assertThat(ReminderPlanner.describe("-1d")).isEqualTo("提前 1 天");
        assertThat(ReminderPlanner.describe(ReminderPlanner.IMMEDIATE)).isEqualTo("马上");
        assertThat(ReminderPlanner.describe("24h")).isEqualTo("24h");   // 旧数据原样透出
    }
}
