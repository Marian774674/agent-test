package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.AskRequest;
import com.czx.qqmessageassistantjava.embedding.EmbeddingIndex;
import com.czx.qqmessageassistantjava.embedding.EmbeddingService;
import com.czx.qqmessageassistantjava.entity.AskTrace;
import com.czx.qqmessageassistantjava.entity.Contact;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.AskTraceMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.retrieval.Candidate;
import com.czx.qqmessageassistantjava.retrieval.Channel;
import com.czx.qqmessageassistantjava.retrieval.QueryPlan;
import com.czx.qqmessageassistantjava.retrieval.RetrievalService;
import com.czx.qqmessageassistantjava.vo.AskResponse;
import com.czx.qqmessageassistantjava.vo.EvidenceVO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * spec §11.1 的验收矩阵（6 个会话、80 条消息，fake {@link EmbeddingService}，不加载 ONNX）。
 *
 * <p>每一行一个具名用例；矩阵行号与 spec 表一一对应。已在各 Task 的单元/集成测试里
 * 钉死的场景（如 {@code AskSingleRoundTest}、{@code AskMultiRoundTest}、
 * {@code RetrievalServiceTest}）在这里仍做一次端到端断言，只在注释里指向原用例，
 * 不复制其整段 fixture。
 *
 * <p><b>为什么不套测试事务：</b>InnoDB FULLTEXT 看不到未提交的行。80 条种子在
 * {@link #seedFixture()} 里一次性插入并提交，类结束时按 id 区间清理；
 * 依赖全文召回的矩阵行因此不需要逐个 {@code NOT_SUPPORTED}。
 *
 * <p><b>fake 向量：</b>同一文本得到同一向量；中文按单字、英文按小写词做确定性哈希桶累加，
 * 共享 token 的文本自然靠近（§11.1 行 2）。行 16 用显式 {@code put} 构造平均向量用例。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalAcceptanceMatrixTest {

    // ---------------------------------------------------------------- 种子数据

    private static final int DIM = 512;

    private static final long FIRST_ID = 9_970_100L;
    private static final long LAST_ID = 9_970_179L;

    /** 行 1：只在「小红私聊」出现的生日。 */
    private static final long ROW1_ID = 9_970_100L;
    /** 行 2：用「他 11 号生日」复述同一事实。 */
    private static final long ROW2_ID = 9_970_101L;
    /** 行 3 之外的填充消息（102–142）。 */
    private static final long PROJECT_FIRST_ID = 9_970_102L;
    private static final long PROJECT_LAST_ID = 9_970_142L;
    /**
     * 行 3 专用语料（独立 ID 空间，不占用 6 会话 / 80 条基础夹具）：
     * 110 条正文完全相同，全文同分时按 id DESC 排名。
     * 第 60 名 = {@link #LEXICAL_RANK_60_ID}；第 102 名 = {@link #LEXICAL_BEYOND_CAP_ID}
     * （超出 {@code MSG_LEXICAL_LIMIT=100}）。
     */
    private static final long LEXICAL_CORPUS_FIRST_ID = 9_971_000L;
    private static final long LEXICAL_CORPUS_LAST_ID = 9_971_109L;
    private static final long LEXICAL_RANK_60_ID = 9_971_050L;
    private static final long LEXICAL_BEYOND_CAP_ID = 9_971_008L;
    private static final int MSG_LEXICAL_CAP = 100;
    private static final String LEXICAL_CORPUS_TOKEN = "分页边界测试词";
    /** 行 4/14：两跳。 */
    private static final long TWO_HOP_A_ID = 9_970_143L;
    private static final long TWO_HOP_B_ID = 9_970_144L;
    /** 行 12：未知称呼出现 3 次。 */
    private static final long UNKNOWN_OCCURRENCE_1 = 9_970_145L;
    private static final long UNKNOWN_OCCURRENCE_2 = 9_970_146L;
    private static final long UNKNOWN_OCCURRENCE_3 = 9_970_147L;
    /** 行 5：记录里出现但不在 contact。 */
    private static final long IDIOT_ID = 9_970_148L;
    /** 行 9：语义关闭后仍靠词法命中。 */
    private static final long LEXICAL_ONLY_ID = 9_970_149L;
    /** 行 10：resolve 失败后按原问题检索。 */
    private static final long RESOLVE_FALLBACK_ID = 9_970_150L;
    /** 行 8：检索前才补索引的新消息。 */
    private static final long NEW_INDEX_ID = 9_970_151L;
    /** 行 15：区间内 / 区间外。 */
    private static final long TIME_IN_ID = 9_970_152L;
    private static final long TIME_OUT_ID = 9_970_153L;
    /** 行 16：只有「三条 query 的平均向量」才能命中的构造用例。 */
    private static final long AVERAGE_TARGET_ID = 9_970_154L;
    private static final long AVERAGE_DISTRACTOR_ID = 9_970_155L;

    private static final long XIAOHONG = 9_970_001L;
    private static final long XIAOMING = 9_970_002L;
    private static final long XUEBA = 9_970_003L;
    private static final long LAOWANG = 9_970_004L;
    private static final long MAMA = 9_970_005L;
    private static final long DUPLICATE_A = 9_970_020L;
    private static final long DUPLICATE_B = 9_970_021L;

    private static final long GROUP_THREE = 9_970_101L;
    private static final long GROUP_OLD_FRIENDS = 9_970_102L;
    private static final long GROUP_FAMILY = 9_970_103L;

    private static final String ALIAS_XIAOMING = "小明甲";
    private static final String ALIAS_XUEBA = "学霸乙";
    private static final String ALIAS_IDIOT = "阿呆丁";
    private static final String ALIAS_UNKNOWN = "路人戊";
    private static final String ALIAS_MISSING = "查无此人己";
    private static final String ALIAS_DUPLICATE = "重名庚";

    private static final String QUESTION_PREFIX = "MX14-";
    /** 清理只认自己的标记，不按裸 ID 区间删别人的行。 */
    private static final String MESSAGE_MARKER = "mx14-seed-";
    private static final String ROW3_MARKER = "mx14-r3-";
    private static final String RESOLVE_FALLBACK_QUESTION = "回退检索关键词戊己";
    private static final String GIBBERISH = "zzqxvbnm14";

    private static final List<String> CHANNEL_KEYS = Arrays.stream(Channel.values())
            .map(Channel::key)
            .toList();

    // ---------------------------------------------------------------- 依赖

    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        @Primary
        FakeEmbeddingService fakeEmbeddingService() {
            return new FakeEmbeddingService();
        }
    }

    static final class FakeEmbeddingService implements EmbeddingService {

        private volatile boolean available;
        private final Map<String, float[]> overrides = new ConcurrentHashMap<>();

        void setAvailable(boolean available) {
            this.available = available;
        }

        void reset() {
            this.available = false;
            this.overrides.clear();
        }

        void put(String text, float[] vector) {
            if (vector == null || vector.length != DIM) {
                throw new IllegalArgumentException("fake vector must be " + DIM + " dims");
            }
            overrides.put(text, vector);
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String modelId() {
            return "fake-matrix";
        }

        @Override
        public int dim() {
            return DIM;
        }

        @Override
        public float[] embed(String text) {
            if (!available) {
                throw new IllegalStateException("fake embedding unavailable");
            }
            float[] mapped = overrides.get(text);
            return mapped != null ? mapped : tokenVector(text);
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        /**
         * 确定性「共享 token」向量：中文单字、英文/数字连续串各占一个哈希桶，最桶内累加后 L2 归一化。
         * 同一文本 → 同一向量；共享 token 的文本余弦为正，不需要 ONNX。
         */
        private static float[] tokenVector(String text) {
            float[] vector = new float[DIM];
            if (text == null) {
                return vector;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (Character.isLetterOrDigit(c) && c < 0x2E80) {
                    ascii.append(c);
                    continue;
                }
                flushToken(vector, ascii);
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    addToken(vector, String.valueOf(c));
                }
            }
            flushToken(vector, ascii);
            return normalize(vector);
        }

        private static void flushToken(float[] vector, StringBuilder ascii) {
            if (ascii.length() > 0) {
                addToken(vector, ascii.toString());
                ascii.setLength(0);
            }
        }

        private static void addToken(float[] vector, String token) {
            int hash = token.hashCode();
            int position = Math.floorMod(hash, DIM);
            float sign = ((hash >>> 16) & 1) == 0 ? 1f : -1f;
            vector[position] += sign;
        }

        private static float[] normalize(float[] vector) {
            double sum = 0;
            for (float value : vector) {
                sum += value * value;
            }
            if (sum == 0) {
                return vector;
            }
            float scale = (float) (1.0 / Math.sqrt(sum));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
            return vector;
        }
    }

    @Autowired
    private AskService askService;

    @Autowired
    private ContactService contactService;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private EmbeddingIndex embeddingIndex;

    @Autowired
    private FakeEmbeddingService fake;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Autowired
    private AskTraceMapper askTraceMapper;

    @MockitoBean
    private DifyWorkflowClient difyWorkflowClient;

    @MockitoBean
    private EmbeddingJobService embeddingJobService;

    // ---------------------------------------------------------------- 夹具生命周期

    @BeforeAll
    void seedFixture() {
        cleanFixture();
        seedMessages();
        contactService.addUser(XIAOMING, ALIAS_XIAOMING, "同学");
        contactService.addUser(DUPLICATE_A, ALIAS_DUPLICATE, "室友");
        contactService.addUser(DUPLICATE_B, ALIAS_DUPLICATE, "同学");
    }

    @AfterAll
    void cleanUp() {
        cleanFixture();
    }

    @BeforeEach
    void resetState() {
        embeddingIndex.reset();
        fake.reset();
        when(embeddingJobService.status())
                .thenAnswer(invocation -> new EmbeddingJobService.EmbeddingStatus(
                        fake.available(), embeddingIndex.size(), 0));
        when(embeddingJobService.indexMissing(anyInt())).thenReturn(0);
        when(difyWorkflowClient.run(eq("ask"), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> inputs = invocation.getArgument(1);
            String stage = String.valueOf(inputs.get("stage"));
            if ("resolve".equals(stage)) {
                return resolve(List.of(), List.of(), List.of());
            }
            if ("expand".equals(stage)) {
                return Map.of();
            }
            return answer("", List.of());
        });
    }

    // ---------------------------------------------------------------- 行 1–16

    /**
     * 行 1：「小明甲生日」只在【小红私聊】里出现 → 命中并带出处。
     * 同一行为在 {@code AskSingleRoundTest#cross_chat_answer_is_found_and_cites_source}
     * 已用更小的 fixture 钉过；这里验证 80 条种子下的端到端出处格式。
     */
    @Test
    void row01_message_in_private_chat_is_found_and_cites_its_source() {
        stubStages(resolve(List.of(ALIAS_XIAOMING), List.of("生日"), List.of("小明甲生日")),
                List.of(answer("小明甲的生日是11月11日", List.of("msg:" + ROW1_ID))),
                List.of());

        AskResponse response = askService.ask(question("小明甲生日是什么时候"));

        assertThat(response.getAnswer()).contains("11月11");
        assertThat(response.getEvidence()).hasSize(1);
        EvidenceVO evidence = response.getEvidence().get(0);
        assertThat(evidence.getId()).isEqualTo(String.valueOf(ROW1_ID));
        assertThat(evidence.getSource()).startsWith("QQ ").contains("小红");
        assertThat(evidence.getQuote()).contains("小明甲生日");
        assertThat(response.getScope().getChannels()).containsOnlyKeys(CHANNEL_KEYS);
    }

    /**
     * 行 2：消息说「他 11 号生日」，问「小明甲生日」→ 语义通道命中
     * （fake 向量按共享 token 把两者置近）。
     */
    @Test
    void row02_paraphrase_is_found_by_semantic_channel() {
        fake.setAvailable(true);
        indexFixture();
        QueryPlan plan = new QueryPlan(Set.of(), List.of(ALIAS_XIAOMING), List.of(),
                List.of("小明甲生日"), null, null);

        List<Candidate> semantic = retrievalService.retrieve(plan, Set.of())
                .hits().get(Channel.MSG_SEMANTIC);

        assertThat(semantic).isNotNull();
        List<String> keys = semantic.stream().map(Candidate::key).toList();
        assertThat(keys).contains("msg:" + ROW2_ID);
        // 「他 11 号生日」与问题共享「生/日」；应排在前列，而不是靠补足 100 条兜底
        assertThat(keys.indexOf("msg:" + ROW2_ID)).isLessThan(5);

        stubStages(resolve(List.of(ALIAS_XIAOMING), List.of("生日"), List.of("小明甲生日")),
                List.of(answer("小明的生日是11号", List.of("msg:" + ROW2_ID))),
                List.of());
        AskResponse response = askService.ask(question("小明甲生日是什么时候"));

        assertThat(response.getAnswer()).contains("11号");
        assertThat(response.getEvidence()).extracting(EvidenceVO::getId)
                .contains(String.valueOf(ROW2_ID));
        assertThat(response.getScope().getChannels().get("msg-semantic")).isPositive();
    }

    /**
     * 行 3：30 条窗口之外的旧消息仍在召回范围内——但召回上限就是通道上限本身。
     *
     * <p>spec 的「排在 1 万条之前」按字面无法构造：§7 明确 msg-lexical 上限 100、
     * msg-person 上限 200，1 万条不会被任何一路取回。这里改为把目标放在
     * 110 条同分全文命中的第 60 名（旧的 30 条窗口之外、仍在通道上限内），
     * 同时把另一条放在第 102 名（超出上限）断言其缺席：
     * 上限降到 50 时目标消失，升到 100 以上时第 102 名出现，两个方向都会失败。
     * 通道级用例见 {@code RetrievalServiceTest#old_message_beyond_thirty_is_retrieved}。
     */
    @Test
    void row03_old_message_outside_thirty_window_is_retrieved_up_to_channel_cap() {
        seedLexicalCorpus();
        try {
            QueryPlan plan = new QueryPlan(Set.of(), List.of(), List.of(),
                    List.of(LEXICAL_CORPUS_TOKEN), null, null);

            List<Candidate> lexical = retrievalService.retrieve(plan, Set.of())
                    .hits().get(Channel.MSG_LEXICAL);

            assertThat(lexical).isNotNull();
            List<String> keys = lexical.stream().map(Candidate::key).toList();
            // 同分时 id DESC：110 条里第 60 名在 0-based 下标 59
            assertThat(lexical).hasSize(MSG_LEXICAL_CAP);
            assertThat(keys).contains("msg:" + LEXICAL_RANK_60_ID);
            assertThat(keys.indexOf("msg:" + LEXICAL_RANK_60_ID))
                    .as("目标在旧的 30 条窗口之外，但仍在通道上限内")
                    .isEqualTo(59);
            assertThat(keys).doesNotContain("msg:" + LEXICAL_BEYOND_CAP_ID);

            stubStages(resolve(List.of(), List.of(), List.of(LEXICAL_CORPUS_TOKEN)),
                    List.of(answer("旧消息仍然可以找到", List.of("msg:" + LEXICAL_RANK_60_ID))),
                    List.of());
            AskResponse response = askService.ask(question(LEXICAL_CORPUS_TOKEN));

            assertThat(response.getAnswer()).isNotEmpty();
            assertThat(response.getEvidence()).extracting(EvidenceVO::getId)
                    .contains(String.valueOf(LEXICAL_RANK_60_ID));
            String rendered = String.valueOf(
                    stageInputs(capturedInputs(), "answer").get(0).get("candidates"));
            assertThat(rendered).contains("msg:" + LEXICAL_RANK_60_ID);
            assertThat(rendered).doesNotContain("msg:" + LEXICAL_BEYOND_CAP_ID);
        } finally {
            cleanLexicalCorpus();
        }
    }

    /**
     * 行 4：两跳。A 会话「小明甲跟学霸乙住一屋」，B 会话「学霸乙叫李四」→ 2 轮内命中李四。
     * 编排层面的同场景见 {@code AskMultiRoundTest#two_hop_uses_round_one_fact_in_round_two_answer}
     * （那里检索是桩掉的）；这里用真实全文召回。
     */
    @Test
    void row04_two_hop_resolves_person_within_two_rounds() {
        // 第 1 轮的检索串刻意不含「学霸乙」：B 会话的消息必须靠 expand 出的实体在第 2 轮才被召回
        stubStages(resolve(List.of(ALIAS_XIAOMING), List.of(), List.of("小明甲跟谁住一屋")),
                List.of(answer("", List.of()),
                        answer("学霸乙就是李四", List.of("msg:" + TWO_HOP_A_ID, "msg:" + TWO_HOP_B_ID))),
                List.of(Map.of("expand_entities", jsonArray(List.of(ALIAS_XUEBA)))));

        AskResponse response = askService.ask(question("小明甲跟谁住一屋"));

        assertThat(response.getScope().getRounds()).isEqualTo(2);
        assertThat(response.getAnswer()).contains("李四");
        assertThat(response.getEvidence()).extracting(EvidenceVO::getId)
                .contains(String.valueOf(TWO_HOP_A_ID), String.valueOf(TWO_HOP_B_ID));

        List<Map<String, Object>> answerInputs = stageInputs(capturedInputs(), "answer");
        assertThat(answerInputs).hasSize(2);
        assertThat(String.valueOf(answerInputs.get(0).get("candidates")))
                .contains("msg:" + TWO_HOP_A_ID)
                .doesNotContain("msg:" + TWO_HOP_B_ID);
        assertThat(String.valueOf(answerInputs.get(1).get("candidates")))
                .contains("msg:" + TWO_HOP_B_ID);
    }

    /**
     * 行 5：问「阿呆丁」，记录里出现过但不在 contact → 不立即反问，能作答并带 scope.message。
     * 同一终态见 {@code AskSingleRoundTest#unknown_alias_can_still_be_answered}。
     */
    @Test
    void row05_unknown_alias_present_in_records_can_answer_with_scope_note() {
        stubStages(resolve(List.of(ALIAS_IDIOT), List.of(), List.of(ALIAS_IDIOT)),
                List.of(answer("阿呆丁下月搬家", List.of("msg:" + IDIOT_ID))),
                List.of());

        AskResponse response = askService.ask(question("阿呆丁最近怎么样"));

        assertThat(response.getNeedClarify()).isNull();
        assertThat(response.getAnswer()).contains("下月搬家");
        assertThat(response.getScope().getMessage()).isEqualTo("未登记的联系人，按名字检索");
        assertThat(response.getEvidence()).extracting(EvidenceVO::getId)
                .contains(String.valueOf(IDIOT_ID));
    }

    /**
     * 行 6：问一件根本没发生的事 → answer="", scope.message 非空，不编。
     * 用 ASCII 乱码做检索串，确保六路召回都不会误命中。
     */
    @Test
    void row06_unhappened_thing_returns_empty_answer_and_message() {
        stubStages(resolve(List.of(), List.of(), List.of(GIBBERISH)), List.of(), List.of());

        AskResponse response = askService.ask(question(GIBBERISH));

        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getEvidence()).isEmpty();
        assertThat(response.getScope().getMessage()).isEqualTo("没有找到相关记录，已查过……");
        assertThat(response.getScope().getRounds()).isEqualTo(1);
    }

    /**
     * 行 7：模型引用候选集外 id → degraded=true，只回证据。
     * 同一降级路径见 {@code AskSingleRoundTest#invalid_used_ids_degrades_to_evidence}。
     */
    @Test
    void row07_out_of_candidate_ids_degrade_to_evidence() {
        stubStages(resolve(List.of(ALIAS_XIAOMING), List.of("生日"), List.of("小明甲生日")),
                List.of(answer("小明的生日是11月11日", List.of("msg:999999999"))),
                List.of());

        AskResponse response = askService.ask(question("小明甲生日是什么时候"));

        assertThat(response.getDegraded()).isTrue();
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getEvidence()).isNotEmpty();
        assertThat(response.getEvidence()).extracting(EvidenceVO::getType).containsOnly("msg");
    }

    /**
     * 行 8：新消息未入索引 → 查询前补跑后命中；补不完时 scope.lag 如实上报。
     *
     * <p>测试 profile 把 {@code app.retrieval.pre-run-index-limit} 调成 3，是 500 的等价代理：
     * 这里让补跑只成功 1 条、status 报告 lag=1，验证 AskService 确实先补跑再读状态。
     * 索引任务本身的补跑逻辑见 {@code AskSingleRoundTest#new_messages_are_indexed_before_retrieval_and_lag_is_reported}。
     *
     * <p><b>scope.lag 这里是「映射」断言，不是「计算」断言</b>：{@code EmbeddingStatus} 是桩，
     * 断言只证明 AskService 把 {@code status.lag()} 原样搬进 {@code AskScopeVO}。
     * 真实 lag 公式由 {@code EmbeddingJobServiceTest#status_reports_lag} 覆盖；
     * 本用例刻意不再套一层 mock-of-mock。
     */
    @Test
    void row08_new_message_is_indexed_before_retrieval_and_lag_reported() {
        fake.setAvailable(true);
        when(embeddingJobService.indexMissing(anyInt())).thenAnswer(invocation -> {
            embeddingIndex.append(NEW_INDEX_ID, fake.embed("补索引测试消息"));
            return 1;
        });
        when(embeddingJobService.status())
                .thenReturn(new EmbeddingJobService.EmbeddingStatus(true, 1, 1));
        stubStages(resolve(List.of(), List.of(), List.of("补索引测试消息")),
                List.of(answer("补索引后可以找到", List.of("msg:" + NEW_INDEX_ID))),
                List.of());

        AskResponse response = askService.ask(question("补索引测试消息"));

        assertThat(response.getScope().getSemantic()).isTrue();
        // 桩里 status.lag()=1，scope 必须原样为 1（>0 满足 spec 行 8 的期望）
        assertThat(response.getScope().getLag()).isEqualTo(1);
        assertThat(response.getScope().getChannels().get("msg-semantic")).isPositive();
        assertThat(response.getEvidence()).extracting(EvidenceVO::getId)
                .contains(String.valueOf(NEW_INDEX_ID));
        verify(embeddingJobService).indexMissing(3);
    }

    /**
     * 行 9：语义服务不可用 → 其余六路仍能答，scope.semantic=false，msg-semantic 键为 null。
     */
    @Test
    void row09_semantic_unavailable_still_answers_via_other_channels() {
        fake.setAvailable(false);
        stubStages(resolve(List.of(), List.of(), List.of("甲子")),
                List.of(answer("语义关闭也能检索到甲子", List.of("msg:" + LEXICAL_ONLY_ID))),
                List.of());

        AskResponse response = askService.ask(question("甲子在哪里"));

        assertThat(response.getScope().getSemantic()).isFalse();
        assertThat(response.getScope().getChannels()).containsOnlyKeys(CHANNEL_KEYS);
        assertThat(response.getScope().getChannels().get("msg-semantic")).isNull();
        assertThat(response.getScope().getChannels().get("msg-lexical")).isPositive();
        assertThat(response.getAnswer()).isNotEmpty();
        assertThat(response.getEvidence()).extracting(EvidenceVO::getId)
                .contains(String.valueOf(LEXICAL_ONLY_ID));
    }

    /**
     * 行 10：Dify resolve 抛错 → 按原问题检索，仍返回结果，不 500。
     * 同一回退见 {@code AskSingleRoundTest#resolve_failure_falls_back_to_the_raw_question}。
     */
    @Test
    void row10_resolve_failure_falls_back_to_raw_question() {
        AskRequest request = question(RESOLVE_FALLBACK_QUESTION);
        when(difyWorkflowClient.run(eq("ask"), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> inputs = invocation.getArgument(1);
            String stage = String.valueOf(inputs.get("stage"));
            if ("resolve".equals(stage)) {
                throw new BusinessException("resolve 挂了");
            }
            if ("expand".equals(stage)) {
                return Map.of();
            }
            return answer("回退后仍能作答", List.of("msg:" + RESOLVE_FALLBACK_ID));
        });

        AskResponse[] holder = new AskResponse[1];
        assertThatCode(() -> holder[0] = askService.ask(request))
                .doesNotThrowAnyException();

        assertThat(holder[0].getAnswer()).isNotEmpty();
        assertThat(holder[0].getDegraded()).isFalse();
        assertThat(holder[0].getEvidence()).extracting(EvidenceVO::getId)
                .contains(String.valueOf(RESOLVE_FALLBACK_ID));
        Map<String, Object> firstCall = capturedInputs().get(0);
        assertThat(firstCall.get("stage")).isEqualTo("resolve");
    }

    /**
     * 行 11：同一 alias 对应两个 sender → 终态第 1 条 needClarify，且 scope.channels 全为 null（没检索）。
     * 候选与「歧义优先」见 {@code AskSingleRoundTest#ambiguous_alias_asks_before_retrieving}。
     */
    @Test
    void row11_ambiguous_alias_clarifies_without_retrieving() {
        stubStages(resolve(List.of(ALIAS_DUPLICATE), List.of(), List.of(GIBBERISH)),
                List.of(), List.of());

        AskResponse response = askService.ask(question("重名庚什么时候生日"));

        assertThat(response.getNeedClarify()).isNotNull();
        assertThat(response.getNeedClarify().getAlias()).isEqualTo(ALIAS_DUPLICATE);
        assertThat(response.getNeedClarify().getCandidates())
                .extracting(candidate -> candidate.getSenderId())
                .containsExactlyInAnyOrder(DUPLICATE_A, DUPLICATE_B);
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getScope().getChannels()).containsOnlyKeys(CHANNEL_KEYS);
        assertThat(response.getScope().getChannels().values()).allMatch(Objects::isNull);
        assertThat(response.getScope().getRounds()).isZero();
        assertThat(response.getScope().getCandidateCnt()).isZero();
        verify(embeddingJobService, never()).indexMissing(anyInt());
    }

    /**
     * 行 12：未知 alias，记录里出现 3 次，答不出 → 终态第 4 条 needClarify 且 occurrences=3。
     * 同一终态见 {@code AskSingleRoundTest#unknown_alias_with_occurrences_asks_with_count}。
     */
    @Test
    void row12_unknown_alias_with_three_occurrences_asks_with_count() {
        stubStages(resolve(List.of(ALIAS_UNKNOWN), List.of(), List.of(GIBBERISH)),
                List.of(answer("", List.of())), List.of());

        AskResponse response = askService.ask(question("路人戊是谁"));

        assertThat(response.getNeedClarify()).isNotNull();
        assertThat(response.getNeedClarify().getAlias()).isEqualTo(ALIAS_UNKNOWN);
        assertThat(response.getNeedClarify().getOccurrences()).isEqualTo(3);
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getScope().getMessage()).contains("3");
    }

    /**
     * 行 13：未知 alias，全库没有 → 终态第 5 条 needClarify=null，scope.message 说明该称呼没出现过。
     * 同一终态见 {@code AskSingleRoundTest#unknown_alias_without_occurrences_is_not_found}。
     */
    @Test
    void row13_absent_alias_reports_not_seen_without_clarify() {
        stubStages(resolve(List.of(ALIAS_MISSING), List.of(), List.of(GIBBERISH)),
                List.of(), List.of());

        AskResponse response = askService.ask(question("查无此人己在哪里"));

        assertThat(response.getNeedClarify()).isNull();
        assertThat(response.getAnswer()).isEmpty();
        assertThat(response.getEvidence()).isEmpty();
        assertThat(response.getScope().getMessage()).contains(ALIAS_MISSING);
    }

    /**
     * 行 14：第 2 轮答案引用第 1 轮候选的 id → 不判越界（跨轮累计生效）。
     * 编排层面的同场景见 {@code AskMultiRoundTest#round_two_may_cite_round_one_id}；
     * 这里第 1 轮的 id 来自真实全文召回。
     */
    @Test
    void row14_round_two_may_cite_round_one_id() {
        stubStages(resolve(List.of(ALIAS_XIAOMING), List.of(), List.of("小明甲跟谁住一屋")),
                List.of(answer("", List.of()),
                        answer("两人合起来是李四",
                                List.of("msg:" + TWO_HOP_A_ID, "msg:" + TWO_HOP_B_ID))),
                List.of(Map.of("expand_queries", jsonArray(List.of(ALIAS_XUEBA)))));

        AskResponse response = askService.ask(question("小明甲跟谁住一屋"));

        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getScope().getRounds()).isEqualTo(2);
        assertThat(response.getEvidence()).extracting(EvidenceVO::getId)
                .containsExactlyInAnyOrder(String.valueOf(TWO_HOP_A_ID), String.valueOf(TWO_HOP_B_ID));
    }

    /**
     * 行 15：time_from/time_to 覆盖某几条消息 → 区间内整体排在区间外之前，区间外仍保留。
     */
    @Test
    void row15_time_range_orders_in_range_first_and_keeps_out_of_range() {
        stubStages(resolve(List.of(), List.of(), List.of("时间分段安排"),
                        "", "2026-10-15T00:00:00", "2026-10-25T23:59:00"),
                List.of(answer("区间内优先", List.of("msg:" + TIME_IN_ID))),
                List.of());

        AskResponse response = askService.ask(question("时间分段安排是什么"));

        Map<String, Object> answerInput = stageInputs(capturedInputs(), "answer").get(0);
        String rendered = String.valueOf(answerInput.get("candidates"));
        int inRange = rendered.indexOf("msg:" + TIME_IN_ID);
        int outOfRange = rendered.indexOf("msg:" + TIME_OUT_ID);
        assertThat(inRange).isGreaterThanOrEqualTo(0);
        assertThat(outOfRange)
                .as("区间外的候选仍保留，只是排在后面")
                .isGreaterThanOrEqualTo(0);
        assertThat(inRange).isLessThan(outOfRange);
        assertThat(response.getAnswer()).isNotEmpty();
    }

    /**
     * 行 16：只有「3 条查询的平均向量」能命中的构造用例 → 语义通道命中（验证平均而非只用原问题）。
     * 通道级用例见 {@code RetrievalServiceTest#semantic_query_uses_average_of_first_three_queries}。
     */
    @Test
    void row16_average_of_three_query_vectors_hits_target() {
        fake.setAvailable(true);
        fake.put("q1", basis(0));
        fake.put("q2", basis(1));
        fake.put("q3", basis(2));
        embeddingIndex.append(AVERAGE_DISTRACTOR_ID, basis(0));
        embeddingIndex.append(AVERAGE_TARGET_ID, normalized(1, 1, 1));

        QueryPlan plan = new QueryPlan(Set.of(), List.of(), List.of(),
                List.of("q1", "q2", "q3"), null, null);
        List<Candidate> semantic = retrievalService.retrieve(plan, Set.of())
                .hits().get(Channel.MSG_SEMANTIC);

        assertThat(semantic).isNotEmpty();
        assertThat(semantic.get(0).key()).isEqualTo("msg:" + AVERAGE_TARGET_ID);

        stubStages(resolve(List.of(), List.of(), List.of("q1", "q2", "q3")),
                List.of(answer("平均向量命中目标", List.of("msg:" + AVERAGE_TARGET_ID))),
                List.of());
        AskResponse response = askService.ask(question("平均向量问题"));

        assertThat(response.getScope().getChannels().get("msg-semantic")).isPositive();
        assertThat(response.getEvidence()).extracting(EvidenceVO::getId)
                .contains(String.valueOf(AVERAGE_TARGET_ID));
    }

    /** spec §11.1 的种子约束：6 个会话、80 条消息。 */
    @Test
    void fixture_is_six_chats_and_eighty_messages() {
        List<MessageLog> rows = messageLogMapper.selectList(
                new LambdaQueryWrapper<MessageLog>().between(MessageLog::getId, FIRST_ID, LAST_ID));

        assertThat(rows).hasSize(80);
        Set<String> chats = rows.stream()
                .map(row -> row.getChatId() == null
                        ? "private:" + row.getSenderId()
                        : "group:" + row.getChatId())
                .collect(Collectors.toSet());
        assertThat(chats).hasSize(6);
    }

    // ---------------------------------------------------------------- 种子

    private void seedMessages() {
        List<MessageLog> rows = new ArrayList<>();

        // 会话 1：小红私聊（行 1）
        rows.add(message(ROW1_ID, null, null, XIAOHONG, "小红",
                "小明甲生日是11月11", at(2026, 10, 20, 20, 0)));
        rows.add(message(IDIOT_ID, null, null, XIAOHONG, "小红",
                "阿呆丁下月搬家", at(2026, 10, 13, 20, 0)));
        for (int i = 0; i < 10; i++) {
            rows.add(message(9_970_166L + i, null, null, XIAOHONG, "小红",
                    "今天天气不错第" + i, at(2026, 10, 1, 9, i)));
        }

        // 会话 2：小明私聊
        rows.add(message(RESOLVE_FALLBACK_ID, null, null, XIAOMING, "小明",
                RESOLVE_FALLBACK_QUESTION + "在这里", at(2026, 10, 15, 20, 0)));
        rows.add(message(NEW_INDEX_ID, null, null, XIAOMING, "小明",
                "补索引测试消息", at(2026, 10, 16, 20, 0)));
        for (int i = 0; i < 10; i++) {
            rows.add(message(9_970_156L + i, null, null, XIAOMING, "小明",
                    "今天天气不错第" + i, at(2026, 10, 2, 9, i)));
        }

        // 会话 3：三班群
        rows.add(message(ROW2_ID, GROUP_THREE, "三班群", XIAOHONG, "小红",
                "他 11 号生日", at(2026, 10, 21, 20, 0)));
        rows.add(message(TWO_HOP_A_ID, GROUP_THREE, "三班群", XIAOMING, "小明",
                "小明甲跟学霸乙住一屋", at(2026, 10, 10, 20, 0)));
        rows.add(message(LEXICAL_ONLY_ID, GROUP_THREE, "三班群", XIAOHONG, "小红",
                "语义关闭仍能检索到甲子", at(2026, 10, 14, 20, 0)));
        rows.add(message(AVERAGE_TARGET_ID, GROUP_THREE, "三班群", XIAOHONG, "小红",
                "平均向量测试目标", at(2026, 10, 17, 20, 0)));
        rows.add(message(AVERAGE_DISTRACTOR_ID, GROUP_THREE, "三班群", XIAOHONG, "小红",
                "平均向量测试干扰", at(2026, 10, 18, 20, 0)));

        // 会话 4：学霸私聊（填充消息；行 3 已改用 9_971_* 专用语料）
        for (long id = PROJECT_FIRST_ID; id <= 9_970_122L; id++) {
            rows.add(message(id, null, null, XUEBA, "学霸",
                    "项目进度", at(2026, 6, 1, 0, (int) (id - PROJECT_FIRST_ID))));
        }

        // 会话 5：老友群（填充 + 两跳 B + 行 12 的 3 次出现）
        for (long id = 9_970_123L; id <= 9_970_132L; id++) {
            rows.add(message(id, GROUP_OLD_FRIENDS, "老友群", LAOWANG, "老王",
                    "项目进度", at(2026, 6, 2, 0, (int) (id - 9_970_123L))));
        }
        rows.add(message(TWO_HOP_B_ID, GROUP_OLD_FRIENDS, "老友群", XUEBA, "学霸",
                "学霸乙叫李四", at(2026, 10, 11, 20, 0)));
        rows.add(message(UNKNOWN_OCCURRENCE_1, GROUP_OLD_FRIENDS, "老友群", LAOWANG, "老王",
                "路人戊来过第1次", at(2026, 10, 12, 20, 0)));
        rows.add(message(UNKNOWN_OCCURRENCE_2, GROUP_OLD_FRIENDS, "老友群", LAOWANG, "老王",
                "路人戊来过第2次", at(2026, 10, 12, 20, 1)));
        rows.add(message(UNKNOWN_OCCURRENCE_3, GROUP_OLD_FRIENDS, "老友群", LAOWANG, "老王",
                "路人戊来过第3次", at(2026, 10, 12, 20, 2)));

        // 会话 6：家庭群（填充 + 行 15 的区间内外）
        for (long id = 9_970_133L; id <= PROJECT_LAST_ID; id++) {
            rows.add(message(id, GROUP_FAMILY, "家庭群", MAMA, "妈妈",
                    "项目进度", at(2026, 6, 3, 0, (int) (id - 9_970_133L))));
        }
        rows.add(message(TIME_IN_ID, GROUP_FAMILY, "家庭群", MAMA, "妈妈",
                "时间分段安排甲", at(2026, 10, 20, 10, 0)));
        rows.add(message(TIME_OUT_ID, GROUP_FAMILY, "家庭群", MAMA, "妈妈",
                "时间分段安排乙", at(2026, 9, 1, 10, 0)));
        for (int i = 0; i < 4; i++) {
            rows.add(message(9_970_176L + i, GROUP_FAMILY, "家庭群", MAMA, "妈妈",
                    "今天天气不错第" + i, at(2026, 10, 3, 9, i)));
        }

        assertThat(rows).hasSize(80);
        rows.forEach(messageLogMapper::insert);
    }

    private static MessageLog message(long id, Long chatId, String chatName, long senderId,
                                      String senderName, String content, LocalDateTime createdAt) {
        MessageLog row = new MessageLog();
        row.setId(id);
        row.setSenderId(senderId);
        row.setSenderName(senderName);
        row.setChatId(chatId);
        row.setChatName(chatName);
        row.setConversationId("mx14-conv-" + id);
        row.setMessageId("mx14-msg-" + id);
        row.setDedupeKey(MESSAGE_MARKER + id);
        row.setDirection("IN");
        row.setMsgType("text");
        row.setContent(content);
        row.setCreatedAt(createdAt);
        return row;
    }

    private static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute);
    }

    /**
     * 行 3 专用语料：110 条正文完全相同，全文同分 → 名次由 id DESC 决定。
     * 独立 ID 空间与 {@link #ROW3_MARKER} 标记，插入前先清掉上次可能残留的同标记行。
     */
    private void seedLexicalCorpus() {
        cleanLexicalCorpus();
        for (long id = LEXICAL_CORPUS_FIRST_ID; id <= LEXICAL_CORPUS_LAST_ID; id++) {
            MessageLog row = new MessageLog();
            row.setId(id);
            row.setSenderId(XIAOHONG);
            row.setSenderName("小红");
            row.setConversationId("mx14-r3-conv-" + id);
            row.setMessageId("mx14-r3-msg-" + id);
            row.setDedupeKey(ROW3_MARKER + id);
            row.setDirection("IN");
            row.setMsgType("text");
            row.setContent(LEXICAL_CORPUS_TOKEN);
            row.setCreatedAt(id == LEXICAL_RANK_60_ID
                    ? LocalDateTime.of(2025, 1, 1, 0, 0)
                    : LocalDateTime.of(2026, 6, 1, 0, 0)
                            .plusMinutes(id - LEXICAL_CORPUS_FIRST_ID));
            messageLogMapper.insert(row);
        }
    }

    private void cleanLexicalCorpus() {
        messageLogMapper.delete(new LambdaQueryWrapper<MessageLog>()
                .likeRight(MessageLog::getDedupeKey, ROW3_MARKER));
    }

    /**
     * 只按本类自己的标记清理：消息按 dedupe_key 前缀，ask_trace 按 question 前缀，
     * contact 按「insert 时的 (sender_id, alias) 精确对」——不按裸 ID 区间误删别人的行。
     */
    private void cleanFixture() {
        messageLogMapper.delete(new LambdaQueryWrapper<MessageLog>()
                .likeRight(MessageLog::getDedupeKey, MESSAGE_MARKER));
        cleanLexicalCorpus();
        askTraceMapper.delete(new LambdaQueryWrapper<AskTrace>()
                .likeRight(AskTrace::getQuestion, QUESTION_PREFIX));
        removeContact(XIAOMING, ALIAS_XIAOMING);
        removeContact(DUPLICATE_A, ALIAS_DUPLICATE);
        removeContact(DUPLICATE_B, ALIAS_DUPLICATE);
    }

    private void removeContact(long senderId, String alias) {
        contactService.remove(new LambdaQueryWrapper<Contact>()
                .eq(Contact::getSenderId, senderId)
                .eq(Contact::getAlias, alias));
    }

    private void indexFixture() {
        messageLogMapper.selectList(new LambdaQueryWrapper<MessageLog>()
                        .between(MessageLog::getId, FIRST_ID, LAST_ID))
                .forEach(row -> {
                    if (row.getContent() != null && !row.getContent().isBlank()) {
                        embeddingIndex.append(row.getId(), fake.embed(row.getContent()));
                    }
                });
    }

    // ---------------------------------------------------------------- 桩与断言辅助

    private AskRequest question(String text) {
        AskRequest request = new AskRequest();
        request.setQuestion(QUESTION_PREFIX + text);
        return request;
    }

    private static Map<String, Object> resolve(List<String> entities, List<String> keywords,
                                               List<String> queries) {
        return resolve(entities, keywords, queries, "", "", "");
    }

    private static Map<String, Object> resolve(List<String> entities, List<String> keywords,
                                               List<String> queries, String timeHint,
                                               String timeFrom, String timeTo) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("entities", jsonArray(entities));
        outputs.put("keywords", jsonArray(keywords));
        outputs.put("queries", jsonArray(queries));
        outputs.put("time_hint", timeHint);
        outputs.put("time_from", timeFrom);
        outputs.put("time_to", timeTo);
        return outputs;
    }

    private static Map<String, Object> answer(String text, List<String> usedIds) {
        return Map.of("answer", text, "used_ids", jsonArray(usedIds));
    }

    private static String jsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** 按 stage 分派：resolve 固定；expand / answer 各自依次出队，用完后回空输出。 */
    private void stubStages(Map<String, Object> resolveOutput,
                            List<Map<String, Object>> answerOutputs,
                            List<Map<String, Object>> expandOutputs) {
        AtomicInteger answers = new AtomicInteger();
        AtomicInteger expands = new AtomicInteger();
        when(difyWorkflowClient.run(eq("ask"), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> inputs = invocation.getArgument(1);
            String stage = String.valueOf(inputs.get("stage"));
            if ("resolve".equals(stage)) {
                return resolveOutput;
            }
            if ("expand".equals(stage)) {
                int index = expands.getAndIncrement();
                return index < expandOutputs.size() ? expandOutputs.get(index) : Map.of();
            }
            int index = answers.getAndIncrement();
            return index < answerOutputs.size() ? answerOutputs.get(index) : answer("", List.of());
        });
    }

    private List<Map<String, Object>> capturedInputs() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(difyWorkflowClient, atLeastOnce()).run(eq("ask"), captor.capture());
        return captor.getAllValues();
    }

    private static List<Map<String, Object>> stageInputs(List<Map<String, Object>> inputs,
                                                        String stage) {
        return inputs.stream()
                .filter(input -> stage.equals(input.get("stage")))
                .toList();
    }

    private static float[] basis(int axis) {
        float[] vector = new float[DIM];
        vector[axis] = 1f;
        return vector;
    }

    private static float[] normalized(float... head) {
        float[] vector = new float[DIM];
        double sum = 0;
        for (int i = 0; i < head.length; i++) {
            vector[i] = head[i];
            sum += head[i] * head[i];
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < head.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }
}
