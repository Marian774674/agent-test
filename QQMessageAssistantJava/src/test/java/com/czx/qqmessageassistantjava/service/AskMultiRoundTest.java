package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.AskRequest;
import com.czx.qqmessageassistantjava.entity.AskTrace;
import com.czx.qqmessageassistantjava.mapper.AskTraceMapper;
import com.czx.qqmessageassistantjava.retrieval.Candidate;
import com.czx.qqmessageassistantjava.retrieval.Channel;
import com.czx.qqmessageassistantjava.retrieval.QueryPlan;
import com.czx.qqmessageassistantjava.retrieval.RetrievalResult;
import com.czx.qqmessageassistantjava.retrieval.RetrievalService;
import com.czx.qqmessageassistantjava.vo.AskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ask 多轮编排（spec §7.6、§7.7、§7.8 / Task 9）。
 *
 * <p>覆盖：两跳（第 1 轮事实 + expand 出的人名 → 第 2 轮命中）、第 2 轮引用第 1 轮 id 不判越界、
 * 最多 3 轮后走「没找到」终态、0 候选仍 expand 一次、跨轮合并保留最好名次、
 * 通道计数跨轮累加（null 与 0 不混）、ask_trace 一次问答只写一行、expand 失败不冒 500。
 *
 * <p>检索用 spy 按轮桩掉，测试不依赖真实 SQL 命中；Dify 用 mock 按 stage 分派。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AskMultiRoundTest {

    private static final List<String> CHANNEL_KEYS = Arrays.stream(Channel.values())
            .map(Channel::key)
            .toList();

    @Autowired
    private AskService askService;

    @Autowired
    private AskTraceMapper askTraceMapper;

    @MockitoBean
    private DifyWorkflowClient difyWorkflowClient;

    @MockitoBean
    private EmbeddingJobService embeddingJobService;

    @MockitoSpyBean
    private RetrievalService retrievalService;

    private final AtomicInteger answerCalls = new AtomicInteger();
    private final AtomicInteger expandCalls = new AtomicInteger();

    @BeforeEach
    void defaultStubs() {
        answerCalls.set(0);
        expandCalls.set(0);
        when(embeddingJobService.status())
                .thenReturn(new EmbeddingJobService.EmbeddingStatus(false, 0, 0));
        doReturn(new RetrievalResult(Map.of()))
                .when(retrievalService).retrieve(any(), anySet());
    }

    // ---------------------------------------------------------------- 两跳

    @Test
    void two_hop_uses_round_one_fact_in_round_two_answer() {
        Candidate room = candidate("msg", 9_930_001L, "小明 · 室友", "小明跟学霸住一屋");
        Candidate realName = candidate("msg", 9_930_002L, "李四", "学霸叫李四");
        stubRetrieval(result(Map.of(Channel.MSG_LEXICAL, List.of(room))),
                result(Map.of(Channel.MSG_MENTION, List.of(realName))));
        stubStages(resolve(),
                List.of(Map.of("expand_entities", "[\"学霸\"]")),
                List.of(Map.of("answer", "", "used_ids", "[]"),
                        Map.of("answer", "学霸就是李四",
                                "used_ids", "[\"msg:9930001\",\"msg:9930002\"]")));

        LocalDateTime before = LocalDateTime.now().withNano(0);
        AskResponse response = askService.ask(question("小明跟谁住一屋"));
        LocalDateTime after = LocalDateTime.now().withNano(0).plusSeconds(1);

        assertThat(response.getAnswer()).contains("李四");
        assertThat(response.getScope().getRounds()).isEqualTo(2);
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getAnswer()).isNotEmpty();

        // R4：expand 看到的是第 1 轮累计候选，time_hint 是最近一次非空提示
        ArgumentCaptor<Map<String, Object>> inputs = ArgumentCaptor.forClass(Map.class);
        // resolve + 第 1 轮 answer + expand + 第 2 轮 answer
        verify(difyWorkflowClient, times(4)).run(eq("ask"), inputs.capture());
        Map<String, Object> expandInput = inputs.getAllValues().stream()
                .filter(value -> "expand".equals(value.get("stage")))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(expandInput.get("candidates"))).contains("msg:9930001");
        assertThat(expandInput.get("time_hint")).isEqualTo("");

        // Dify 用 current_time 换算相对时间：resolve / expand 都要带，answer 不带
        for (Map<String, Object> input : inputs.getAllValues()) {
            String stage = String.valueOf(input.get("stage"));
            if ("answer".equals(stage)) {
                assertThat(input).as("answer 不需要 current_time").doesNotContainKey("current_time");
                continue;
            }
            assertThat(input).as("%s 应带 current_time", stage).containsKey("current_time");
            LocalDateTime sent = LocalDateTime.parse(String.valueOf(input.get("current_time")));
            assertThat(sent).as("%s 的 current_time", stage).isBetween(before, after);
        }

        // R7：第 2 轮检索排除第 1 轮已经见过的 key
        ArgumentCaptor<Set<String>> excludes = ArgumentCaptor.forClass(Set.class);
        verify(retrievalService, times(2)).retrieve(any(QueryPlan.class), excludes.capture());
        assertThat(excludes.getAllValues().get(0)).isEmpty();
        assertThat(excludes.getAllValues().get(1)).containsExactly("msg:9930001");
    }

    @Test
    void round_two_may_cite_round_one_id() {
        Candidate first = candidate("msg", 9_930_011L, "小明 · 室友", "小明跟学霸住一屋");
        Candidate second = candidate("msg", 9_930_012L, "李四", "学霸叫李四");
        stubRetrieval(result(Map.of(Channel.MSG_LEXICAL, List.of(first))),
                result(Map.of(Channel.MSG_MENTION, List.of(second))));
        stubStages(resolve(),
                List.of(Map.of("expand_queries", "[\"学霸\"]")),
                List.of(Map.of("answer", "", "used_ids", "[]"),
                        Map.of("answer", "两人合起来是李四",
                                "used_ids", "[\"msg:9930011\",\"msg:9930012\"]")));

        AskResponse response = askService.ask(question("小明跟谁住一屋"));

        // 跨轮累计后，第 1 轮的 id 依然在候选集里，不判越界
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getAnswer()).isNotEmpty();
        assertThat(response.getEvidence()).extracting(e -> e.getId())
                .containsExactlyInAnyOrder("9930011", "9930012");
        assertThat(response.getScope().getRounds()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 轮次上限与空候选

    @Test
    void stops_after_three_rounds_and_says_not_found() {
        stubRetrieval();
        stubStages(resolve(),
                List.of(Map.of("expand_queries", "[\"第一轮改写\"]"),
                        Map.of("expand_queries", "[\"第二轮改写\"]"),
                        Map.of("expand_queries", "[\"第三轮改写\"]")),
                List.of());

        AskResponse response = askService.ask(question("完全不存在的事情" + System.nanoTime()));

        assertThat(response.getScope().getRounds()).isEqualTo(3);
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getScope().getCandidateCnt()).isZero();
        assertThat(response.getScope().getMessage()).contains("没有找到相关记录");
        // 第 3 轮之后不再 expand
        assertThat(expandCalls.get()).isEqualTo(2);
    }

    @Test
    void empty_candidates_still_expand_once() {
        Candidate realName = candidate("msg", 9_930_021L, "李四", "学霸叫李四");
        stubRetrieval(result(Map.of()), result(Map.of(Channel.MSG_MENTION, List.of(realName))));
        stubStages(resolve(),
                List.of(Map.of("expand_queries", "[\"学霸\"]")),
                List.of(Map.of("answer", "李四", "used_ids", "[\"msg:9930021\"]")));

        AskResponse response = askService.ask(question("小明跟谁住一屋"));

        // 第 1 轮 0 候选仍要 expand 一次（可能是措辞问题）
        assertThat(expandCalls.get()).isEqualTo(1);
        assertThat(response.getScope().getRounds()).isEqualTo(2);
        assertThat(response.getAnswer()).contains("李四");
        // 第 1 轮没有候选，所以没有作答调用；第 2 轮才答一次
        assertThat(answerCalls.get()).isEqualTo(1);
    }

    @Test
    void expand_failure_stops_the_rounds_without_500() {
        Candidate first = candidate("msg", 9_930_031L, "小明 · 室友", "小明跟学霸住一屋");
        stubRetrieval(result(Map.of(Channel.MSG_LEXICAL, List.of(first))));
        when(difyWorkflowClient.run(eq("ask"), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> inputs = invocation.getArgument(1);
            String stage = String.valueOf(inputs.get("stage"));
            if ("resolve".equals(stage)) {
                return resolve();
            }
            if ("expand".equals(stage)) {
                throw new BusinessException("expand 挂了");
            }
            return Map.of("answer", "", "used_ids", "[]");
        });

        AskResponse[] holder = new AskResponse[1];
        assertThatCode(() -> holder[0] = askService.ask(question("小明跟谁住一屋")))
                .doesNotThrowAnyException();

        assertThat(holder[0].getScope().getRounds()).isEqualTo(1);
        assertThat(holder[0].getAnswer()).isEmpty();
        assertThat(holder[0].getScope().getMessage()).isEqualTo("找到相关记录，但没能得出结论");
    }

    // ---------------------------------------------------------------- 跨轮合并（R2）

    /** 后续轮次排名更好的候选必须整体前移，而不是保留早先的较差名次。 */
    @Test
    void merge_keeps_the_best_rank_across_rounds() {
        Candidate a = candidate("msg", 1L, "a", "A");
        Candidate x = candidate("msg", 2L, "x", "X");
        Candidate b = candidate("msg", 3L, "b", "B");
        Candidate c = candidate("msg", 4L, "c", "C");
        RetrievalResult round1 = result(Map.of(Channel.MSG_LEXICAL, List.of(a, x, b)));
        RetrievalResult round2 = result(Map.of(Channel.MSG_LEXICAL, List.of(b, c)));

        RetrievalResult merged = round1.merge(round2);

        // b 在第 1 轮第 3 名、第 2 轮第 1 名 → 合并后升到第 2 名
        assertThat(merged.hits().get(Channel.MSG_LEXICAL))
                .extracting(Candidate::key)
                .containsExactly("msg:1", "msg:3", "msg:2", "msg:4");
        // 两边都没有的通道保持缺失，不补 null
        assertThat(merged.hits()).doesNotContainKey(Channel.MEMO);
        // 只在一侧出现的通道也要并进来
        RetrievalResult withNote = result(Map.of(Channel.NOTE, List.of(a)));
        assertThat(round1.merge(withNote).hits()).containsKey(Channel.NOTE);
        assertThat(merged.hits()).containsOnlyKeys(Channel.MSG_LEXICAL);
    }

    // ---------------------------------------------------------------- 通道计数（R9）

    @Test
    void scope_channels_sum_across_rounds_and_keep_null_when_never_run() {
        Candidate n1 = candidate("note", 1L, "a", "A");
        Candidate n2 = candidate("note", 2L, "b", "B");
        Candidate n3 = candidate("note", 3L, "c", "C");
        Candidate n4 = candidate("note", 4L, "d", "D");
        Candidate n5 = candidate("note", 5L, "e", "E");
        stubRetrieval(result(Map.of(Channel.NOTE, List.of(n1, n2))),
                result(Map.of(Channel.NOTE, List.of(n3, n4, n5),
                        Channel.MSG_SEMANTIC, List.of())));
        // 第 1 轮答不出 → expand 给新词；第 2 轮仍答不出 → 第 2 次 expand 给空，结束
        stubStages(resolve(),
                List.of(Map.of("expand_queries", "[\"第二轮\"]")),
                List.of(Map.of("answer", "", "used_ids", "[]")));

        AskResponse response = askService.ask(question("通道计数" + System.nanoTime()));

        Map<String, Integer> channels = response.getScope().getChannels();
        assertThat(response.getScope().getRounds()).isEqualTo(2);
        assertThat(channels).containsOnlyKeys(CHANNEL_KEYS);
        // 跑过的通道按轮累加：2 + 3
        assertThat(channels.get("note")).isEqualTo(5);
        // 第 2 轮跑了语义通道但 0 命中：是 0，不是 null
        assertThat(channels.get("msg-semantic")).isZero();
        // 从没跑过的通道保持 null
        assertThat(channels.get("memo")).isNull();
        assertThat(channels.get("msg-lexical")).isNull();
        // candidate_cnt 是累计去重数（预算截断前）
        assertThat(response.getScope().getCandidateCnt()).isEqualTo(5);
    }

    // ---------------------------------------------------------------- 轨迹

    @Test
    void ask_trace_has_one_row_with_counts_and_channels() {
        String question = "多轮轨迹" + System.nanoTime();
        Candidate first = candidate("msg", 9_930_041L, "小明 · 室友", "小明跟学霸住一屋");
        Candidate second = candidate("msg", 9_930_042L, "李四", "学霸叫李四");
        stubRetrieval(result(Map.of(Channel.MSG_LEXICAL, List.of(first))),
                result(Map.of(Channel.MSG_PERSON, List.of(second))));
        stubStages(resolve(),
                List.of(Map.of("expand_queries", "[\"学霸\"]")),
                List.of(Map.of("answer", "", "used_ids", "[]"),
                        Map.of("answer", "李四", "used_ids", "[\"msg:9930042\"]")));

        AskResponse response = askService.ask(question(question));
        var scope = response.getScope();

        List<AskTrace> traces = askTraceMapper.selectList(
                new LambdaQueryWrapper<AskTrace>().eq(AskTrace::getQuestion, question));
        assertThat(traces).hasSize(1);
        AskTrace trace = traces.get(0);
        assertThat(trace.getRounds()).isEqualTo(scope.getRounds()).isEqualTo(2);
        assertThat(trace.getCandidateCnt()).isEqualTo(scope.getCandidateCnt()).isEqualTo(2);
        String expectedChannels = scope.getChannels().entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(","));
        assertThat(trace.getChannels()).isEqualTo(expectedChannels);
        assertThat(trace.getAnswer()).isEqualTo("李四");
        assertThat(trace.getDegraded()).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private AskRequest question(String text) {
        AskRequest request = new AskRequest();
        request.setQuestion(text);
        return request;
    }

    private static Map<String, Object> resolve() {
        return Map.of("entities", "[]", "keywords", "[]", "time_hint", "",
                "queries", "[\"原问题\"]");
    }

    private static Candidate candidate(String type, long id, String label, String content) {
        return new Candidate(type, id, label, content, null, "", "");
    }

    private static RetrievalResult result(Map<Channel, List<Candidate>> hits) {
        return new RetrievalResult(hits);
    }

    /** 按轮桩掉检索；超出的轮次返回空结果。 */
    private void stubRetrieval(RetrievalResult... perRound) {
        AtomicInteger index = new AtomicInteger();
        doAnswer(invocation -> {
            int round = index.getAndIncrement();
            return round < perRound.length ? perRound[round] : new RetrievalResult(Map.of());
        }).when(retrievalService).retrieve(any(QueryPlan.class), anySet());
    }

    /** 按 stage 分派：resolve 固定；expand / answer 按各自队列依次出队，用完后回空输出。 */
    private void stubStages(Map<String, Object> resolve,
                            List<Map<String, Object>> expandOutputs,
                            List<Map<String, Object>> answerOutputs) {
        when(difyWorkflowClient.run(eq("ask"), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> inputs = invocation.getArgument(1);
            String stage = String.valueOf(inputs.get("stage"));
            if ("resolve".equals(stage)) {
                return resolve;
            }
            if ("expand".equals(stage)) {
                int index = expandCalls.getAndIncrement();
                return index < expandOutputs.size() ? expandOutputs.get(index) : Map.of();
            }
            int index = answerCalls.getAndIncrement();
            return index < answerOutputs.size()
                    ? answerOutputs.get(index) : Map.of("answer", "", "used_ids", "[]");
        });
    }
}
