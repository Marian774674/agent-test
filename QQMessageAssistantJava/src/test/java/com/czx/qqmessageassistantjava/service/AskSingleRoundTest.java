package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.AskRequest;
import com.czx.qqmessageassistantjava.entity.AskTrace;
import com.czx.qqmessageassistantjava.entity.MemoryNote;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.AskTraceMapper;
import com.czx.qqmessageassistantjava.mapper.MemoryNoteMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.retrieval.Channel;
import com.czx.qqmessageassistantjava.vo.AskResponse;
import com.czx.qqmessageassistantjava.vo.EvidenceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ask 单轮编排（spec §7.0–§7.5、§7.7）。
 *
 * <p>覆盖：跨会话证据与出处、认人歧义优先于一切、未知 alias 的出现次数、降级与三种「没结论」
 * 终态、resolve/answer 失败不冒 500、检索前补索引与 lag、七通道键恒在。
 *
 * <p>依赖 {@code MATCH…AGAINST} 的用例必须 {@link Propagation#NOT_SUPPORTED}：
 * InnoDB FULLTEXT 看不到测试事务里尚未提交的行；这些用例在 finally 里显式删除种子数据。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AskSingleRoundTest {

    private static final Long SENDER_A = 9_980_001L;
    private static final Long SENDER_B = 9_980_002L;
    private static final Long SENDER_C = 9_980_003L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 28, 21, 3);

    private static final List<String> CHANNEL_KEYS = Arrays.stream(Channel.values())
            .map(Channel::key)
            .toList();

    @Autowired
    private AskService askService;

    @Autowired
    private ContactService contactService;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Autowired
    private AskTraceMapper askTraceMapper;

    @Autowired
    private MemoryNoteMapper memoryNoteMapper;

    @MockitoBean
    private DifyWorkflowClient difyWorkflowClient;

    @MockitoBean
    private EmbeddingJobService embeddingJobService;

    @BeforeEach
    void defaultStubs() {
        // 测试不加载真模型：默认语义不可用、没有待补索引。
        when(embeddingJobService.status())
                .thenReturn(new EmbeddingJobService.EmbeddingStatus(false, 0, 0));
    }

    // ---------------------------------------------------------------- 13 个用例

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cross_chat_answer_is_found_and_cites_source() {
        long id = 9_920_001L;
        String question = "小明的生日是什么时候";
        messageLogMapper.deleteById(id);
        askTraceMapper.delete(new LambdaQueryWrapper<AskTrace>().eq(AskTrace::getQuestion, question));
        try {
            insertMessage(id, null, "小红私聊", 9_920_101L, "小红", "小明生日是11月11", AT);
            stubStages(
                    Map.of("entities", "[\"小明\"]",
                            "keywords", "[\"生日\"]",
                            "queries", "[\"小明生日\"]",
                            "time_hint", "", "time_from", "", "time_to", ""),
                    Map.of("answer", "小明的生日是11月11日",
                            "used_ids", "[\"msg:" + id + "\"]"));

            AskResponse response = askService.ask(question(question));

            assertThat(response.getAnswer()).contains("11月11");
            assertThat(response.getEvidence()).isNotEmpty();
            assertThat(response.getEvidence().get(0).getSource()).contains("QQ");
            assertThat(response.getEvidence().get(0).getQuote()).contains("小明生日");
            assertThat(response.getScope().getChannels()).containsOnlyKeys(CHANNEL_KEYS);
        } finally {
            messageLogMapper.deleteById(id);
            askTraceMapper.delete(new LambdaQueryWrapper<AskTrace>().eq(AskTrace::getQuestion, question));
        }
    }

    @Test
    void ambiguous_alias_asks_before_retrieving() {
        String alias = "重名-" + System.nanoTime();
        contactService.addUser(SENDER_A, alias, "室友");
        contactService.addUser(SENDER_B, alias, "同学");
        stubStages(resolve(List.of(alias)), Map.of());

        AskResponse response = askService.ask(question("这个人什么时候生日"));

        assertThat(response.getNeedClarify()).isNotNull();
        assertThat(response.getNeedClarify().getAlias()).isEqualTo(alias);
        assertThat(response.getNeedClarify().getCandidates())
                .extracting(c -> c.getSenderId())
                .containsExactlyInAnyOrder(SENDER_A, SENDER_B);
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getScope().getChannels()).containsOnlyKeys(CHANNEL_KEYS);
        assertThat(response.getScope().getChannels().values()).allMatch(java.util.Objects::isNull);
        assertThat(response.getScope().getRounds()).isZero();
        assertThat(response.getScope().getCandidateCnt()).isZero();
        // 歧义优先：完全不进入检索前的补索引与七路召回
        verify(embeddingJobService, never()).indexMissing(anyInt());
        verify(difyWorkflowClient, times(1)).run(eq("ask"), anyMap());
    }

    @Test
    void unknown_alias_with_occurrences_asks_with_count() {
        String alias = "路人甲" + System.nanoTime() + "号";
        String question = "无关问题" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            insertMessage(null, 9_920_900L, "某群", SENDER_C, "某人", alias + "来过" + i, AT.plusMinutes(i));
        }
        stubStages(resolve(List.of(alias)), Map.of());

        AskResponse response = askService.ask(question(question));

        assertThat(response.getNeedClarify()).isNotNull();
        assertThat(response.getNeedClarify().getAlias()).isEqualTo(alias);
        assertThat(response.getNeedClarify().getOccurrences()).isEqualTo(3);
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getScope().getMessage()).isNotEmpty().contains("3");
    }

    @Test
    void unknown_alias_without_occurrences_is_not_found() {
        String alias = "查无此人" + System.nanoTime();
        String question = "无关问题" + System.nanoTime();
        stubStages(resolve(List.of(alias)), Map.of());

        AskResponse response = askService.ask(question(question));

        assertThat(response.getNeedClarify()).isNull();
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getEvidence()).isEmpty();
        assertThat(response.getScope().getMessage()).isNotEmpty().contains(alias);
    }

    @Test
    void invalid_used_ids_degrades_to_evidence() {
        String alias = "老周" + System.nanoTime();
        contactService.addUser(SENDER_A, alias, "同事");
        insertMessage(null, 9_920_901L, "三班群", SENDER_A, "老周", "我上周出差了", AT);
        stubStages(resolve(List.of(alias)),
                Map.of("answer", "老周在撒谎", "used_ids", "[\"msg:99999999\"]"));

        AskResponse response = askService.ask(question("老周说了什么"));

        assertThat(response.getDegraded()).isTrue();
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getEvidence()).isNotEmpty();
        assertThat(response.getEvidence()).extracting(EvidenceVO::getType).contains("msg");
    }

    @Test
    void answer_without_answer_and_with_candidates_reports_message() {
        String alias = "小郑" + System.nanoTime();
        contactService.addUser(SENDER_A, alias, "同学");
        insertMessage(null, 9_920_901L, "三班群", SENDER_A, "小郑", "最近在准备考试", AT);
        stubStages(resolve(List.of(alias)), Map.of("answer", "", "used_ids", "[]"));

        AskResponse response = askService.ask(question("小郑最近怎么样"));

        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getNeedClarify()).isNull();
        assertThat(response.getEvidence()).isNotEmpty();
        assertThat(response.getScope().getMessage()).isEqualTo("找到相关记录，但没能得出结论");
    }

    @Test
    void no_candidates_reports_not_found() {
        String question = "无关问题" + System.nanoTime();
        stubStages(resolve(List.of()), Map.of());

        AskResponse response = askService.ask(question(question));

        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getNeedClarify()).isNull();
        assertThat(response.getEvidence()).isEmpty();
        assertThat(response.getScope().getMessage()).isEqualTo("没有找到相关记录，已查过……");
        assertThat(response.getScope().getCandidateCnt()).isZero();
        assertThat(response.getScope().getRounds()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void unknown_alias_can_still_be_answered() {
        long id = 9_920_002L;
        String alias = "阿呆" + (System.nanoTime() % 100_000) + "测试";
        String question = "阿呆最近怎么样";
        messageLogMapper.deleteById(id);
        askTraceMapper.delete(new LambdaQueryWrapper<AskTrace>().eq(AskTrace::getQuestion, question));
        try {
            insertMessage(id, null, "小红私聊", 9_920_102L, "小红", alias + "说他下月搬家", AT);
            stubStages(resolve(List.of(alias)),
                    Map.of("answer", "阿呆下月搬家", "used_ids", "[\"msg:" + id + "\"]"));

            AskResponse response = askService.ask(question(question));

            assertThat(response.getNeedClarify()).isNull();
            assertThat(response.getAnswer()).isNotEmpty();
            assertThat(response.getScope().getMessage()).contains("未登记的联系人，按名字检索");
        } finally {
            messageLogMapper.deleteById(id);
            askTraceMapper.delete(new LambdaQueryWrapper<AskTrace>().eq(AskTrace::getQuestion, question));
        }
    }

    @Test
    void ambiguous_alias_takes_priority_over_a_unique_one() {
        String unique = "阿黄" + System.nanoTime();
        String ambiguous = "小明" + System.nanoTime();
        String missing = "无此人" + System.nanoTime();
        contactService.addUser(SENDER_A, unique, "同事");
        contactService.addUser(SENDER_A, ambiguous, "室友");
        contactService.addUser(SENDER_B, ambiguous, "同学");
        stubStages(resolve(List.of(unique, missing, ambiguous)), Map.of());

        AskResponse response = askService.ask(question("小明和阿黄什么时候考试"));

        assertThat(response.getNeedClarify()).isNotNull();
        assertThat(response.getNeedClarify().getAlias()).isEqualTo(ambiguous);
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getEvidence()).isEmpty();
        verify(embeddingJobService, never()).indexMissing(anyInt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void resolve_failure_falls_back_to_the_raw_question() {
        long id = 9_920_003L;
        String question = "回退验证关键词" + (System.nanoTime() % 1_000_000);
        messageLogMapper.deleteById(id);
        askTraceMapper.delete(new LambdaQueryWrapper<AskTrace>().eq(AskTrace::getQuestion, question));
        try {
            insertMessage(id, 9_920_902L, "测试群", SENDER_C, "某人", question, AT);
            when(difyWorkflowClient.run(eq("ask"), anyMap())).thenAnswer(invocation -> {
                Map<String, Object> inputs = invocation.getArgument(1);
                if ("resolve".equals(inputs.get("stage"))) {
                    throw new BusinessException("resolve 挂了");
                }
                return Map.of("answer", "", "used_ids", "[]");
            });

            AskResponse response = askService.ask(question(question));

            assertThat(response.getAnswer()).isEmpty();
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(difyWorkflowClient, times(2)).run(eq("ask"), captor.capture());
            Map<String, Object> answerInputs = captor.getAllValues().get(1);
            assertThat(answerInputs.get("stage")).isEqualTo("answer");
            assertThat(String.valueOf(answerInputs.get("candidates"))).contains("msg:" + id);
        } finally {
            messageLogMapper.deleteById(id);
            askTraceMapper.delete(new LambdaQueryWrapper<AskTrace>().eq(AskTrace::getQuestion, question));
        }
    }

    @Test
    void answer_failure_degrades_without_500() {
        String alias = "李四" + System.nanoTime();
        contactService.addUser(SENDER_A, alias, "同事");
        insertMessage(null, 9_920_901L, "三班群", SENDER_A, "李四", "明天开会", AT);
        Map<String, Object> resolve = resolve(List.of(alias));
        when(difyWorkflowClient.run(eq("ask"), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> inputs = invocation.getArgument(1);
            if ("answer".equals(inputs.get("stage"))) {
                throw new BusinessException("answer 挂了");
            }
            return resolve;
        });

        AskResponse[] holder = new AskResponse[1];
        assertThatCode(() -> holder[0] = askService.ask(question("李四明天做什么")))
                .doesNotThrowAnyException();

        assertThat(holder[0].getDegraded()).isTrue();
        assertThat(holder[0].getAnswer()).isEmpty();
        assertThat(holder[0].getEvidence()).isNotEmpty();
    }

    @Test
    void new_messages_are_indexed_before_retrieval_and_lag_is_reported() {
        when(embeddingJobService.indexMissing(3)).thenReturn(3);
        when(embeddingJobService.status())
                .thenReturn(new EmbeddingJobService.EmbeddingStatus(false, 3, 2));
        stubStages(resolve(List.of()), Map.of());

        AskResponse response = askService.ask(question("无关问题" + System.nanoTime()));

        assertThat(response.getScope().getLag()).isEqualTo(2);
        assertThat(response.getScope().getSemantic()).isFalse();
        // 上限来自 app.retrieval.pre-run-index-limit（测试 profile 置为 3）
        verify(embeddingJobService, times(1)).indexMissing(3);
    }

    @Test
    void scope_always_has_the_seven_channel_keys() {
        stubStages(resolve(List.of()), Map.of());

        AskResponse response = askService.ask(question("无关问题" + System.nanoTime()));

        Map<String, Integer> channels = response.getScope().getChannels();
        assertThat(channels).hasSize(7);
        assertThat(channels).containsOnlyKeys(CHANNEL_KEYS);
        assertThat(channels.get("msg-semantic")).isNull();
        for (String key : CHANNEL_KEYS) {
            if (!"msg-semantic".equals(key)) {
                assertThat(channels.get(key)).isZero();
            }
        }
        assertThat(response.getScope().getSemantic()).isFalse();
        assertThat(response.getScope().getRounds()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 额外覆盖

    @Test
    void ask_trace_is_written_with_scope_counts() {
        String alias = "轨迹" + System.nanoTime();
        String question = "轨迹记录问题" + System.nanoTime();
        contactService.addUser(SENDER_A, alias, "同事");
        insertMessage(null, 9_920_901L, "三班群", SENDER_A, "轨迹当事人", "轨迹测试内容", AT);
        stubStages(resolve(List.of(alias)), Map.of("answer", "", "used_ids", "[]"));

        askService.ask(question(question));

        List<AskTrace> traces = askTraceMapper.selectList(
                new LambdaQueryWrapper<AskTrace>().eq(AskTrace::getQuestion, question));
        assertThat(traces).hasSize(1);
        AskTrace trace = traces.get(0);
        assertThat(trace.getRounds()).isEqualTo(1);
        assertThat(trace.getCandidateCnt()).isGreaterThanOrEqualTo(1);
        assertThat(trace.getChannels()).contains("msg-person");
        assertThat(trace.getDegraded()).isFalse();
        assertThat(trace.getEntities()).contains(alias);
    }

    /**
     * note 证据的出处按 source_msg_ids 回查一次 message_log 组装（spec §7.5 / R9）：
     * 群聊格式「群名 · 人名 · MM-dd HH:mm」，quote 取第一条来源正文。
     */
    @Test
    void note_evidence_carries_group_source_and_quote() {
        String alias = "证据" + System.nanoTime();
        contactService.addUser(SENDER_A, alias, "同学");
        MessageLog message = insertMessage(null, 9_920_901L, "三班群", SENDER_A, "小明",
                "小明生日是11月11", AT);
        MemoryNote note = seedNote(SENDER_A, "生日", "11月11日", String.valueOf(message.getId()));
        stubStages(resolve(List.of(alias)),
                Map.of("answer", "小明的生日是11月11日",
                        "used_ids", "[\"note:" + note.getId() + "\"]"));

        AskResponse response = askService.ask(question("她什么时候生日"));

        assertThat(response.getEvidence()).hasSize(1);
        EvidenceVO evidence = response.getEvidence().get(0);
        assertThat(evidence.getType()).isEqualTo("note");
        assertThat(evidence.getSource()).contains("三班群").contains("小明");
        assertThat(evidence.getQuote()).contains("小明生日");
    }

    // ---------------------------------------------------------------- helpers

    private AskRequest question(String text) {
        AskRequest request = new AskRequest();
        request.setQuestion(text);
        return request;
    }

    private Map<String, Object> resolve(List<String> entities) {
        List<String> quoted = new ArrayList<>();
        for (String entity : entities) {
            quoted.add("\"" + entity + "\"");
        }
        return Map.of("entities", "[" + String.join(",", quoted) + "]",
                "keywords", "[]", "time_hint", "");
    }

    /** 按 stage 分派：同一个 task 跑两次，resolve 与 answer 的返回值不同。 */
    private void stubStages(Map<String, Object> resolve, Map<String, Object> answer) {
        when(difyWorkflowClient.run(eq("ask"), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> inputs = invocation.getArgument(1);
            return "resolve".equals(inputs.get("stage")) ? resolve : answer;
        });
    }

    private MessageLog insertMessage(Long id, Long chatId, String chatName, Long senderId,
                                     String senderName, String content, LocalDateTime createdAt) {
        MessageLog row = new MessageLog();
        row.setId(id);
        row.setSenderId(senderId);
        row.setSenderName(senderName);
        row.setChatId(chatId);
        row.setChatName(chatName);
        row.setConversationId("conv-" + System.nanoTime());
        row.setMessageId("msg-" + System.nanoTime());
        row.setDedupeKey("ask-single-" + System.nanoTime());
        row.setDirection("IN");
        row.setMsgType("text");
        row.setContent(content);
        row.setCreatedAt(createdAt);
        messageLogMapper.insert(row);
        return row;
    }

    private MemoryNote seedNote(Long subject, String predicate, String value, String sourceMsgIds) {
        MemoryNote note = new MemoryNote();
        note.setSubjectSenderId(subject);
        note.setPredicate(predicate);
        note.setValue(value);
        note.setSourceMsgIds(sourceMsgIds);
        note.setCreatedAt(LocalDateTime.now());
        memoryNoteMapper.insert(note);
        return note;
    }
}
