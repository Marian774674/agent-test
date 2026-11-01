package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.AgentOpsRequest;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoChangeLog;
import com.czx.qqmessageassistantjava.vo.MemoReminderVO;
import com.czx.qqmessageassistantjava.mapper.MemoChangeLogMapper;
import com.czx.qqmessageassistantjava.vo.AgentOpsResult;
import com.czx.qqmessageassistantjava.vo.MemoOpenItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentOpServiceTest {

    private static final long OWNER = 8801L;

    @Autowired private AgentOpService agentOpService;
    @Autowired private MemoService memoService;
    @Autowired private MemoChangeLogMapper changeLogMapper;

    private AgentOpsRequest request(String op) {
        AgentOpsRequest request = new AgentOpsRequest();
        request.setEventId(UUID.randomUUID().toString());
        request.setOp(op);
        request.setSenderId(String.valueOf(OWNER));
        request.setChatId("");
        return request;
    }

    private List<MemoReminderVO> remindersOf(Long memoId) {
        return memoService.reminders(memoId);
    }

    private static LocalDateTime soon() {
        return LocalDateTime.now().withNano(0);
    }

    private Memo seed(int importance, LocalDateTime due) {
        MemoCreateRequest req = new MemoCreateRequest();
        req.setSenderId(OWNER);
        req.setTitle("班会");
        req.setImportance(importance);
        req.setDueTime(due);
        return memoService.create(req);
    }

    @Test
    void createClampsImportanceAndFallsBackCategory() {
        AgentOpsRequest req = request("memo.create");
        req.setTitle("班会");
        req.setContent("明天下午 2 点 教学楼 S109");
        req.setImportance("9");
        req.setCategory("乱七八糟");
        // 空格分隔的写法（模型最常产出），且相对今天算，避免测试过期
        req.setDueTime(soon().plusDays(4).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        AgentOpsResult result = agentOpService.execute(req);

        Memo memo = memoService.getById(result.getMemoId());
        assertThat(memo.getImportance()).isEqualTo(5);
        assertThat(memo.getCategory()).isEqualTo("其他");
        assertThat(memo.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getMessage()).startsWith("记下了：");
        // 落不认识的分类 = 「其他」，计划按类型走：提前 1 小时 + 到期各一次
        assertThat(result.getReminders()).hasSize(2);
        assertThat(result.getReminders().get(0).getOffsetLabel()).isEqualTo("-1h");
        assertThat(result.getReminders().get(1).getOffsetLabel()).isEqualTo("截止");
    }

    @Test
    void updateMovesDueTimeAndRebuildsReminders() {
        Memo memo = seed(3, soon().plusDays(3));
        List<Long> oldReminderIds = remindersOf(memo.getId()).stream().map(MemoReminderVO::getId).toList();
        assertThat(oldReminderIds).isNotEmpty();

        String newDue = soon().plusDays(5).toString(); // ISO 写法
        AgentOpsRequest req = request("memo.update");
        req.setMemoId(String.valueOf(memo.getId()));
        req.setDueTime(newDue);
        req.setContent("后天下午 4 点 教学楼 S111");

        AgentOpsResult result = agentOpService.execute(req);

        Memo updated = memoService.getById(memo.getId());
        assertThat(updated.getContent()).contains("S111");
        assertThat(updated.getDueTime()).isEqualTo(LocalDateTime.parse(newDue));
        assertThat(result.getMessage()).startsWith("改好了：");
        assertThat(result.isReplayed()).isFalse();
        assertThat(result.getReminders())
                .allSatisfy(r -> assertThat(r.getRemindAt()).isAfter(LocalDateTime.now()));
        // 旧提醒不能被留在 PENDING，否则改期后还会按旧时间推
        assertThat(remindersOf(memo.getId()))
                .filteredOn(r -> oldReminderIds.contains(r.getId()))
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo("CANCELLED"));
    }

    @Test
    void updateOfAnotherSendersMemoIsRejected() {
        Memo memo = seed(3, LocalDateTime.now().plusDays(2));

        AgentOpsRequest req = request("memo.update");
        req.setSenderId("7999");
        req.setMemoId(String.valueOf(memo.getId()));
        req.setTitle("抢改");

        assertThatThrownBy(() -> agentOpService.execute(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("备忘录不存在");
    }

    @Test
    void sameEventIdOnlyWritesOnce() {
        AgentOpsRequest req = request("memo.create");
        req.setTitle("交实验报告");
        req.setImportance("3");

        AgentOpsResult first = agentOpService.execute(req);
        AgentOpsResult second = agentOpService.execute(req);

        assertThat(second.isReplayed()).isTrue();
        assertThat(second.getMemoId()).isEqualTo(first.getMemoId());
        assertThat(second.getMessage()).isEqualTo(first.getMessage());
        assertThat(changeLogMapper.selectByEventId(req.getEventId())).isNotNull();
    }

    @Test
    void closeCancelsPendingReminders() {
        Memo memo = seed(5, LocalDateTime.now().plusDays(2));

        AgentOpsRequest req = request("memo.close");
        req.setMemoId(String.valueOf(memo.getId()));
        req.setStatus("CANCELLED");

        AgentOpsResult result = agentOpService.execute(req);

        assertThat(result.getStatus()).isEqualTo("CANCELLED");
        assertThat(result.getReminders()).isEmpty();
        assertThat(result.getMessage()).startsWith("已取消：");
        assertThat(memoService.getById(memo.getId()).getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void closeWithoutMemoIdTargetsLatestOpenMemo() {
        Memo older = seed(3, LocalDateTime.now().plusDays(4));
        Memo newer = seed(3, LocalDateTime.now().plusDays(6));

        AgentOpsRequest req = request("memo.close");
        req.setStatus("DONE");

        AgentOpsResult result = agentOpService.execute(req);

        assertThat(result.getMemoId()).isEqualTo(newer.getId());
        assertThat(memoService.getById(older.getId()).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void closeRejectsUnknownStatus() {
        Memo memo = seed(3, LocalDateTime.now().plusDays(1));
        AgentOpsRequest req = request("memo.close");
        req.setMemoId(String.valueOf(memo.getId()));
        req.setStatus("DELETE");

        assertThatThrownBy(() -> agentOpService.execute(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("status");
    }

    @Test
    void updateWithoutAnyFieldIsRejected() {
        Memo memo = seed(3, LocalDateTime.now().plusDays(1));
        AgentOpsRequest req = request("memo.update");
        req.setMemoId(String.valueOf(memo.getId()));

        assertThatThrownBy(() -> agentOpService.execute(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有要修改的内容");
    }

    @Test
    void updateRejectsStatusField() {
        Memo memo = seed(3, LocalDateTime.now().plusDays(1));
        AgentOpsRequest req = request("memo.update");
        req.setMemoId(String.valueOf(memo.getId()));
        req.setStatus("DONE");

        assertThatThrownBy(() -> agentOpService.execute(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("memo.close");
    }

    @Test
    void missingEventIdIsRejected() {
        AgentOpsRequest req = request("memo.create");
        req.setEventId("  ");
        req.setTitle("x");

        assertThatThrownBy(() -> agentOpService.execute(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void historyRecordsBeforeAndAfter() {
        Memo memo = seed(3, LocalDateTime.now().plusDays(3));
        AgentOpsRequest req = request("memo.update");
        req.setMemoId(String.valueOf(memo.getId()));
        req.setTitle("班会（改地点）");

        agentOpService.execute(req);

        List<MemoChangeLog> history = agentOpService.history(memo.getId());
        assertThat(history).hasSize(1);
        MemoChangeLog entry = history.get(0);
        assertThat(entry.getOp()).isEqualTo("UPDATE");
        assertThat(entry.getSource()).isEqualTo("AGENT");
        assertThat(entry.getBeforeJson()).contains("班会");
        assertThat(entry.getAfterJson()).contains("改地点");
    }

    @Test
    void openMemosExcludesTerminalAndSortsByDueTime() {
        Memo late = seed(3, LocalDateTime.now().plusDays(9));
        Memo soon = seed(3, LocalDateTime.now().plusDays(1));
        Memo done = seed(3, LocalDateTime.now().plusDays(2));
        MemoCreateRequest noDue = new MemoCreateRequest();
        noDue.setSenderId(OWNER);
        noDue.setTitle("没时间的待办");
        noDue.setImportance(2);
        Memo noDueTime = memoService.create(noDue);

        AgentOpsRequest close = request("memo.close");
        close.setMemoId(String.valueOf(done.getId()));
        close.setStatus("DONE");
        agentOpService.execute(close);

        List<MemoOpenItem> items = memoService.openMemos(OWNER, 20);

        assertThat(items).extracting(MemoOpenItem::getId)
                .contains(soon.getId(), late.getId(), noDueTime.getId())
                .doesNotContain(done.getId());
        assertThat(items.get(0).getId()).isEqualTo(soon.getId());
        assertThat(items.get(items.size() - 1).getId()).isEqualTo(noDueTime.getId());
    }
}
