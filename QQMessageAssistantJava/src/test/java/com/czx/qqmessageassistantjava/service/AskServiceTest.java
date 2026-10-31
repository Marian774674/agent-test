package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.dto.AskRequest;
import com.czx.qqmessageassistantjava.dto.ClarifyAnswer;
import com.czx.qqmessageassistantjava.entity.Contact;
import com.czx.qqmessageassistantjava.entity.MemoryNote;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MemoryNoteMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.vo.AskResponse;
import com.czx.qqmessageassistantjava.vo.EvidenceVO;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 查询链路：认人（Dify 抽名字 + Spring 查表）、候选集（SQL）、答题（Dify）、校验（Spring）。
 * 最重要的两条：同名的两个人必须反问；模型引用的证据必须真的在候选集里。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AskServiceTest {

    @Autowired
    private AskService askService;

    @Autowired
    private ContactService contactService;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Autowired
    private MemoryNoteMapper memoryNoteMapper;

    @MockitoBean
    private DifyWorkflowClient difyWorkflowClient;

    private static final Long SENDER_A = 9_940_001L;
    private static final Long SENDER_B = 9_940_002L;

    /**
     * 新契约（spec §7.7 终态第 5 条）：未知 alias 且全库没有出现过 → 反问没有意义，
     * 直接按「没找到」返回，needClarify 为 null；没有候选时也不再调 answer。
     * 旧契约在认人阶段立刻反问，这条测试保留「不会因此 500、不会硬答」的原始意图。
     */
    @Test
    void unknownAliasWithoutOccurrences_isNotFoundAndNeverCallsTheAnswerStage() {
        String alias = "查无此人-" + System.nanoTime();
        stub(resolve(List.of(alias)), Map.of());

        AskResponse response = askService.ask(question("这个人是谁"));

        assertThat(response.getNeedClarify()).isNull();
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getEvidence()).isEmpty();
        assertThat(response.getScope()).isNotNull();
        assertThat(response.getScope().getMessage()).isNotEmpty();
        verify(difyWorkflowClient, times(1)).run(eq("ask"), anyMap());
    }

    @Test
    void twoSendersShareAnAlias_returnsNeedClarifyWithBothCandidates() {
        String alias = "小明-" + System.nanoTime();
        contactService.addUser(SENDER_A, alias, "室友");
        contactService.addUser(SENDER_B, alias, "同学");
        stub(resolve(List.of(alias)), Map.of());

        AskResponse response = askService.ask(question("小明什么时候生日"));

        assertThat(response.getNeedClarify()).isNotNull();
        assertThat(response.getNeedClarify().getCandidates())
                .extracting(c -> c.getSenderId())
                .containsExactlyInAnyOrder(SENDER_A, SENDER_B);
        verify(difyWorkflowClient, times(1)).run(eq("ask"), anyMap());
    }

    @Test
    void clarifyWritesAUserContactAndThenAnswers() {
        String alias = "阿黄-" + System.nanoTime();
        Long msgId = seedMessage(SENDER_A);
        stub(resolve(List.of(alias)),
                Map.of("answer", "阿黄下周考试",
                        "used_ids", "[\"msg:" + msgId + "\"]"));

        AskRequest request = question("阿黄什么时候考试");
        ClarifyAnswer clarify = new ClarifyAnswer();
        clarify.setAlias(alias);
        clarify.setSenderId(SENDER_A);
        clarify.setFrom("就是他");
        request.setClarify(clarify);

        AskResponse response = askService.ask(request);

        assertThat(response.getNeedClarify()).isNull();
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getAnswer()).isEqualTo("阿黄下周考试");
        assertThat(contactService.findByAlias(alias)).singleElement()
                .satisfies(c -> {
                    assertThat(c.getSenderId()).isEqualTo(SENDER_A);
                    assertThat(c.getSource()).isEqualTo("USER");
                });
    }

    @Test
    void usedIdsOutsideTheCandidateSet_degradeToEvidenceOnly() {
        String alias = "老周-" + System.nanoTime();
        contactService.addUser(SENDER_B, alias, "同事");
        seedMessage(SENDER_B);
        stub(resolve(List.of(alias)),
                Map.of("answer", "老周在撒谎", "used_ids", "[\"msg:99999999\"]"));

        AskResponse response = askService.ask(question("老周说了什么"));

        assertThat(response.getDegraded()).isTrue();
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getEvidence()).isNotEmpty();
        assertThat(response.getEvidence()).extracting(EvidenceVO::getType).contains("msg");
    }

    @Test
    void resolvedEntity_feedsHisNotesAndMessagesAsCandidates() {
        String alias = "小郑-" + System.nanoTime();
        contactService.addUser(SENDER_A, alias, "同学");
        seedMessage(SENDER_A);
        seedNote(SENDER_A, "生日", "11月11日");
        stub(resolve(List.of(alias)), Map.of("answer", "", "used_ids", "[]"));

        askService.ask(question("小郑的生日"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(difyWorkflowClient, times(2)).run(eq("ask"), captor.capture());
        String candidates = String.valueOf(captor.getAllValues().get(1).get("candidates"));
        assertThat(candidates).contains("note:").contains("msg:");
        Assertions.assertThat(candidates).contains("11月11日");
    }

    private AskRequest question(String text) {
        AskRequest request = new AskRequest();
        request.setQuestion(text);
        return request;
    }

    private Map<String, Object> resolve(List<String> entities) {
        List<String> quoted = new ArrayList<>();
        for (String e : entities) {
            quoted.add("\"" + e + "\"");
        }
        return Map.of("entities", "[" + String.join(",", quoted) + "]",
                "keywords", "[]", "time_hint", "");
    }

    /** 按 stage 分派：同一个 task 跑两次，resolve 与 answer 的返回值不同 */
    private void stub(Map<String, Object> resolve, Map<String, Object> answer) {
        when(difyWorkflowClient.run(eq("ask"), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> inputs = invocation.getArgument(1);
            return "resolve".equals(inputs.get("stage")) ? resolve : answer;
        });
    }

    private Long seedMessage(Long senderId) {
        MessageLog row = new MessageLog();
        row.setSenderId(senderId);
        row.setSenderName("当事人");
        row.setChatId(9_940_100L);
        row.setChatName("三班群");
        row.setDirection("IN");
        row.setMsgType("text");
        row.setContent("我下周考试");
        row.setCreatedAt(LocalDateTime.now());
        row.setDedupeKey("ask-" + System.nanoTime());
        messageLogMapper.insert(row);
        return row.getId();
    }

    private void seedNote(Long subject, String predicate, String value) {
        MemoryNote note = new MemoryNote();
        note.setSubjectSenderId(subject);
        note.setPredicate(predicate);
        note.setValue(value);
        note.setSourceMsgIds("1");
        note.setCreatedAt(LocalDateTime.now());
        memoryNoteMapper.insert(note);
    }
}
