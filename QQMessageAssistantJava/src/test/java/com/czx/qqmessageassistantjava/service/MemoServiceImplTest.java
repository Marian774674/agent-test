package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoReminder;
import com.czx.qqmessageassistantjava.mapper.MemoReminderMapper;
import com.czx.qqmessageassistantjava.vo.MemoReminderVO;
import com.czx.qqmessageassistantjava.vo.ReminderPlanItemVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoServiceImplTest {

    @Autowired private MemoService memoService;
    @Autowired private MemoReminderMapper memoReminderMapper;

    private Memo create(int importance, LocalDateTime due) {
        return create(null, importance, due);
    }

    private Memo create(String category, int importance, LocalDateTime due) {
        MemoCreateRequest req = new MemoCreateRequest();
        req.setSenderId(9001L);
        req.setTitle("t");
        req.setCategory(category);
        req.setImportance(importance);
        req.setDueTime(due);
        return memoService.create(req);
    }

    @Test
    void patchRejectsImportanceOutOfRange() {
        Memo memo = create(3, LocalDateTime.now().plusDays(2));
        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setImportance(6);

        assertThatThrownBy(() -> memoService.patch(memo.getId(), patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("importance");
    }

    @Test
    void createRejectsUnknownCategory() {
        MemoCreateRequest req = new MemoCreateRequest();
        req.setSenderId(9002L);
        req.setTitle("t");
        req.setImportance(3);
        req.setCategory("游戏");

        assertThatThrownBy(() -> memoService.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("category");
    }

    @Test
    void pastDueTimeDoesNotCreatePastReminders() {
        Memo memo = create(5, LocalDateTime.now().minusDays(2));
        long past = memoReminderMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemoReminder>()
                        .eq(MemoReminder::getMemoId, memo.getId())
                        .lt(MemoReminder::getRemindAt, LocalDateTime.now()));
        assertThat(past).isZero();
    }

    @Test
    void categoryDecidesTheReminderPlanInTimeOrder() {
        Memo memo = create("学业", 3, LocalDateTime.now().plusDays(3));

        List<MemoReminderVO> plan = memoService.reminders(memo.getId());

        assertThat(plan).extracting(MemoReminderVO::getOffsetLabel)
                .containsExactly("-1d@20:00", "-1h", "截止");
        assertThat(plan).allMatch(r -> "PENDING".equals(r.getStatus()));
        // 前端直接显示中文，不再自己维护一张映射表
        assertThat(plan).extracting(MemoReminderVO::getOffsetText)
                .containsExactly("前一天 20:00", "提前 1 小时", "到期时");
    }

    @Test
    void sameDueTimeDifferentCategoryGetsDifferentPlan() {
        LocalDateTime due = LocalDateTime.now().plusDays(3);

        Memo study = create("学业", 3, due);
        Memo social = create("社交", 3, due);

        assertThat(memoService.reminders(study.getId())).extracting(MemoReminderVO::getOffsetLabel)
                .containsExactly("-1d@20:00", "-1h", "截止");
        assertThat(memoService.reminders(social.getId())).extracting(MemoReminderVO::getOffsetLabel)
                .containsExactly("-2h", "-30m", "截止");
    }

    @Test
    void lowImportanceHasNoReminders() {
        Memo memo = create("学业", 1, LocalDateTime.now().plusDays(3));

        assertThat(memoService.reminders(memo.getId())).isEmpty();
    }

    @Test
    void patchRecalculatesRemindersWhenCategoryChanges() {
        Memo memo = create("健康", 3, LocalDateTime.now().plusDays(3));
        assertThat(memoService.reminders(memo.getId())).hasSize(2);   // -30m + 到期

        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setCategory("学业");
        memoService.patch(memo.getId(), patch);

        // 旧的两档（-30m、到期）被置为 CANCELLED 留档，新计划三档都是 PENDING
        List<MemoReminderVO> plan = memoService.reminders(memo.getId());
        assertThat(plan).filteredOn(r -> "PENDING".equals(r.getStatus()))
                .extracting(MemoReminderVO::getOffsetLabel)
                .containsExactly("-1d@20:00", "-1h", "截止");
        assertThat(plan).filteredOn(r -> "CANCELLED".equals(r.getStatus())).hasSize(2);
    }

    @Test
    void replanSwapsOldPlanForTheCurrentPolicy() {
        // 先不带时间建，绕过排程；再直接把 dueTime 写进库，模拟「部署前就存在的备忘」
        Memo memo = create("学业", 5, null);
        memo.setDueTime(LocalDateTime.now().plusDays(3));
        memoService.updateById(memo);
        for (String legacy : List.of("24h", "2h", "30min")) {
            MemoReminder old = new MemoReminder();
            old.setMemoId(memo.getId());
            old.setOffsetLabel(legacy);
            old.setRemindAt(LocalDateTime.now().plusDays(1));
            old.setStatus("PENDING");
            memoReminderMapper.insert(old);
        }

        memoService.replan(memo.getId());

        List<MemoReminderVO> plan = memoService.reminders(memo.getId());
        assertThat(plan).filteredOn(r -> "PENDING".equals(r.getStatus()))
                .extracting(MemoReminderVO::getOffsetLabel)
                .containsExactly("-1d@20:00", "-1h", "截止");
        // 老档不删，只作废，便于回溯
        assertThat(plan).filteredOn(r -> "CANCELLED".equals(r.getStatus())).hasSize(3);
    }

    @Test
    void previewPlanUsesTheSamePolicyAsRealScheduling() {
        LocalDateTime due = LocalDateTime.now().plusDays(3).withNano(0);

        List<ReminderPlanItemVO> preview = memoService.previewPlan(
                "学业", due.toString(), 4);

        assertThat(preview).extracting(ReminderPlanItemVO::getText)
                .containsExactly("前一天 20:00", "提前 1 小时", "到期时");
        assertThat(preview).extracting(ReminderPlanItemVO::getRemindAt)
                .containsExactlyElementsOf(
                        memoService.reminders(create("学业", 4, due).getId()).stream()
                                .map(MemoReminderVO::getRemindAt).toList());
    }

    @Test
    void previewPlanWithoutDueTimeIsEmpty() {
        assertThat(memoService.previewPlan("学业", null, 4)).isEmpty();
        assertThat(memoService.previewPlan("学业", "  ", 4)).isEmpty();
    }

    @Test
    void patchRejectsUnknownCategory() {
        Memo memo = create(3, LocalDateTime.now().plusDays(2));
        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setCategory("游戏");

        assertThatThrownBy(() -> memoService.patch(memo.getId(), patch))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("category");
    }

    @Test
    void doneMemoCancelsPendingReminders() {
        Memo memo = create(5, LocalDateTime.now().plusDays(3));

        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setStatus("DONE");
        memoService.patch(memo.getId(), patch);

        assertThat(memoService.reminders(memo.getId()))
                .noneMatch(r -> "PENDING".equals(r.getStatus()));
    }

    @Test
    void patchCanClearDueTimeAndCancelsReminders() {
        Memo memo = create(5, LocalDateTime.now().plusDays(3));

        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setClearDueTime(true);
        Memo updated = memoService.patch(memo.getId(), patch);

        assertThat(updated.getDueTime()).isNull();
        assertThat(memoService.reminders(memo.getId()))
                .noneMatch(r -> "PENDING".equals(r.getStatus()));
    }

    @Test
    void dueTimeAcceptsCommonLlmFormats() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        MemoCreateRequest spaced = mapper.readValue(
                "{\"dueTime\":\"2026-10-29 19:00\"}", MemoCreateRequest.class);
        assertThat(spaced.getDueTime()).isEqualTo(LocalDateTime.of(2026, 10, 29, 19, 0));

        MemoCreateRequest zoned = mapper.readValue(
                "{\"dueTime\":\"2026-10-29T19:00:00+08:00\"}", MemoCreateRequest.class);
        assertThat(zoned.getDueTime()).isEqualTo(LocalDateTime.of(2026, 10, 29, 19, 0));

        MemoCreateRequest dateOnly = mapper.readValue(
                "{\"dueTime\":\"2026-10-29\"}", MemoCreateRequest.class);
        assertThat(dateOnly.getDueTime()).isEqualTo(LocalDateTime.of(2026, 10, 29, 0, 0));

        MemoCreateRequest blank = mapper.readValue(
                "{\"dueTime\":\"\"}", MemoCreateRequest.class);
        assertThat(blank.getDueTime()).isNull();
    }
}
