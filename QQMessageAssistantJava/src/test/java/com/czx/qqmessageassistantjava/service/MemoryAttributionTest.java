package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.entity.MemoryNote;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MemoryNoteMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 归因闸门（spec §6）：主体只能是本批发言人，或 known_contacts 里、且 alias 在其证据正文中出现过的人。
 *
 * <p>这是「模型把小红的事记到小明头上」的唯一可靠拦截点；提示词只负责倾向，校验负责正确。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryAttributionTest {

    @Autowired
    private MemoryJobService memoryJobService;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Autowired
    private MemoryNoteMapper memoryNoteMapper;

    @Autowired
    private ContactService contactService;

    @Autowired
    private AppSettingService appSettingService;

    @MockitoBean
    private DifyWorkflowClient difyWorkflowClient;

    /** 本批发言人 */
    private static final Long SENDER_XIAOHONG = 9_950_001L;
    private static final Long CHAT = 9_950_100L;
    /** 本批没发言、但 known_contacts 里有的第三方 */
    private static final Long SENDER_XIAOMING = 9_950_002L;

    @Test
    void third_party_fact_is_attributed_when_alias_in_message() {
        String alias = uniqueAlias("小明");
        String value = uniqueValue("生日");
        // 小红（本批发言人）在消息里提到小明
        Long msgId = seedMessage(SENDER_XIAOHONG, "小红", alias + "的生日是 11 月 11 日");
        contactService.addUser(SENDER_XIAOMING, alias, "同学");
        armCursor(msgId);
        stubExtract(notes(noteJson(SENDER_XIAOMING, value, msgId)));

        assertThat(memoryJobService.runOnce()).isEqualTo(1);

        assertThat(notesOf(SENDER_XIAOMING)).extracting(MemoryNote::getValue).contains(value);
    }

    @Test
    void third_party_fact_is_dropped_when_alias_absent() {
        String alias = uniqueAlias("小明");
        String value = uniqueValue("生日");
        // 消息正文里没有「小明」→ 模型把小红的话记到小明头上，整条丢弃
        Long msgId = seedMessage(SENDER_XIAOHONG, "小红", "今天天气不错");
        contactService.addUser(SENDER_XIAOMING, alias, "同学");
        armCursor(msgId);
        stubExtract(notes(noteJson(SENDER_XIAOMING, value, msgId)));

        assertThat(memoryJobService.runOnce()).isZero();

        assertThat(notesOf(SENDER_XIAOMING)).extracting(MemoryNote::getValue).doesNotContain(value);
    }

    @Test
    void subject_not_in_batch_or_contacts_is_dropped() {
        String value = uniqueValue("生日");
        Long msgId = seedMessage(SENDER_XIAOHONG, "小红", "纯文本，没有任何可归因的名字");
        armCursor(msgId);
        // 主体 9_950_999 既不在本批发言人里，也不在 known_contacts 里
        stubExtract(notes(noteJson(9_950_999L, value, msgId)));

        assertThat(memoryJobService.runOnce()).isZero();

        assertThat(notesOf(9_950_999L)).extracting(MemoryNote::getValue).doesNotContain(value);
    }

    @Test
    void alias_in_a_different_notes_evidence_does_not_authorize_the_subject() {
        String alias = uniqueAlias("小明");
        String authorizedValue = uniqueValue("小红的事");
        String thirdPartyValue = uniqueValue("小明的事");
        // msg1 提到小明，但它是「小红」这条 note 的证据；msg2 才是「小明」这条 note 的证据，正文里没有小明
        Long msg1 = seedMessage(SENDER_XIAOHONG, "小红", alias + "最近在准备考试");
        Long msg2 = seedMessage(SENDER_XIAOHONG, "小红", "今天天气不错");
        contactService.addUser(SENDER_XIAOMING, alias, "同学");
        armCursor(msg1);
        stubExtract(notes(noteJson(SENDER_XIAOHONG, authorizedValue, msg1),
                noteJson(SENDER_XIAOMING, thirdPartyValue, msg2)));

        // 本批发言人的 note 不需要 alias 校验；第三方按「自己的证据」校验 → 只写小红那条
        assertThat(memoryJobService.runOnce()).isEqualTo(1);

        assertThat(notesOf(SENDER_XIAOHONG)).extracting(MemoryNote::getValue).contains(authorizedValue);
        assertThat(notesOf(SENDER_XIAOMING)).extracting(MemoryNote::getValue).doesNotContain(thirdPartyValue);
    }

    private Long seedMessage(Long senderId, String senderName, String content) {
        MessageLog row = new MessageLog();
        row.setSenderId(senderId);
        row.setSenderName(senderName);
        row.setChatId(CHAT);
        row.setChatName("三班群");
        row.setDirection("IN");
        row.setMsgType("text");
        row.setContent(content);
        row.setCreatedAt(LocalDateTime.now());
        row.setDedupeKey("attribution-" + System.nanoTime());
        messageLogMapper.insert(row);
        return row.getId();
    }

    /** 游标停在第一条之前：本批恰好只含本测试插入的消息 */
    private void armCursor(Long firstId) {
        appSettingService.putAll(Map.of(
                "memory_job_enabled", "1",
                "memory_job_cursor", String.valueOf(firstId - 1)));
    }

    private List<MemoryNote> notesOf(Long subject) {
        return memoryNoteMapper.selectList(new LambdaQueryWrapper<MemoryNote>()
                .eq(MemoryNote::getSubjectSenderId, subject));
    }

    private void stubExtract(String notesJson) {
        when(difyWorkflowClient.run(eq("extract"), anyMap()))
                .thenReturn(Map.of("contacts", "[]", "notes", notesJson));
    }

    private static String noteJson(Long subject, String value, Long msgId) {
        return "{\"subject_sender_id\":\"" + subject + "\",\"predicate\":\"生日\","
                + "\"value\":\"" + value + "\",\"source_msg_ids\":[" + msgId + "]}";
    }

    /** 把若干条 note 对象拼成一个 JSON 数组 */
    private static String notes(String... noteObjects) {
        return "[" + String.join(",", noteObjects) + "]";
    }

    private static String uniqueAlias(String base) {
        return base + "-" + System.nanoTime();
    }

    private static String uniqueValue(String base) {
        return base + "-" + System.nanoTime();
    }
}
