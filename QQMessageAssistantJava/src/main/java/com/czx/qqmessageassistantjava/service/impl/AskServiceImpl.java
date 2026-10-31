package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.dto.AskRequest;
import com.czx.qqmessageassistantjava.dto.ClarifyAnswer;
import com.czx.qqmessageassistantjava.entity.AskTrace;
import com.czx.qqmessageassistantjava.entity.Contact;
import com.czx.qqmessageassistantjava.entity.MemoryNote;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.AskTraceMapper;
import com.czx.qqmessageassistantjava.mapper.MemoryNoteMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.retrieval.Candidate;
import com.czx.qqmessageassistantjava.retrieval.Channel;
import com.czx.qqmessageassistantjava.retrieval.QueryPlan;
import com.czx.qqmessageassistantjava.retrieval.RetrievalResult;
import com.czx.qqmessageassistantjava.retrieval.RetrievalService;
import com.czx.qqmessageassistantjava.retrieval.RrfFusion;
import com.czx.qqmessageassistantjava.retrieval.SourceFormat;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.AskService;
import com.czx.qqmessageassistantjava.service.ContactService;
import com.czx.qqmessageassistantjava.service.EmbeddingJobService;
import com.czx.qqmessageassistantjava.vo.AskResponse;
import com.czx.qqmessageassistantjava.vo.AskScopeVO;
import com.czx.qqmessageassistantjava.vo.ClarifyCandidateVO;
import com.czx.qqmessageassistantjava.vo.ClarifyStateVO;
import com.czx.qqmessageassistantjava.vo.EvidenceVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一句话查回去（spec §7）：resolve 查询理解 → 认人 → 检索前补索引 → 七路召回 → RRF 融合
 * → Dify 作答 → Spring 校验证据 → 终态表 → 写 {@code ask_trace}。
 *
 * <p>各步失败都只降级、不 500：resolve 失败按原问题检索，召回失败按无候选处理，
 * 作答失败降级为证据列表，expand 失败或没给出新词就结束多轮，
 * 轨迹写不进去只记日志。
 *
 * <p>多轮（spec §7.6）：最多 3 轮（含第 1 轮）。每轮检索后把结果<b>跨轮累计</b>，
 * 答不出（空答案 / 作答失败 / 证据越界）才调 {@code stage=expand} 要新检索串与新实体；
 * 累计集在每次作答前才做一次 30k/150 的预算截断；终态表只在循环结束后判定一次。
 */
@Service
public class AskServiceImpl implements AskService {

    private static final Logger log = LoggerFactory.getLogger(AskServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter SPACE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 传给 Dify 的当前时间；截到秒，固定 ISO-8601 本地时间格式。 */
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** spec §7.4：融合后的字符预算与条目上限。 */
    static final int CHAR_BUDGET = 30_000;
    static final int MAX_ITEMS = 150;
    /** 降级/反问时回给的候选条数（spec §7.7「候选前 5 条」）。 */
    static final int EVIDENCE_LIMIT = 5;

    /** spec §7.1：queries 最多 4 条；词法通道每条都跑，不能无上限。 */
    static final int MAX_QUERIES = 4;

    /** spec §7.6：最多 3 轮，含第 1 轮。 */
    static final int MAX_ROUNDS = 3;

    /** expand 阶段给模型看的累计候选摘要上限（字符）。 */
    static final int EXPAND_CHAR_LIMIT = 12_000;

    static final int KNOWN_CONTACTS_LIMIT = 200;
    static final int QUOTE_LIMIT = 200;
    static final int SOURCE_MAX = 3;
    static final int OCCURRENCE_DISPLAY_CAP = 999;

    static final int TRACE_QUESTION_LIMIT = 512;
    static final int TRACE_ENTITIES_LIMIT = 512;
    static final int TRACE_QUERIES_LIMIT = 1024;
    static final int TRACE_ANSWER_LIMIT = 1024;

    static final String MESSAGE_UNREGISTERED = "未登记的联系人，按名字检索";
    static final String MESSAGE_WITH_CANDIDATES = "找到相关记录，但没能得出结论";
    static final String MESSAGE_NOT_FOUND = "没有找到相关记录，已查过……";

    private final DifyWorkflowClient difyWorkflowClient;
    private final ContactService contactService;
    private final MessageLogMapper messageLogMapper;
    private final MemoryNoteMapper memoryNoteMapper;
    private final AskTraceMapper askTraceMapper;
    private final RetrievalService retrievalService;
    private final EmbeddingJobService embeddingJobService;
    private final AppSettingService appSettingService;

    /** 检索前同步补跑的向量条数上限（spec §5.2）；测试 profile 调小以便断言。 */
    @Value("${app.retrieval.pre-run-index-limit:500}")
    private int preRunIndexLimit = 500;

    public AskServiceImpl(DifyWorkflowClient difyWorkflowClient,
                          ContactService contactService,
                          MessageLogMapper messageLogMapper,
                          MemoryNoteMapper memoryNoteMapper,
                          AskTraceMapper askTraceMapper,
                          RetrievalService retrievalService,
                          EmbeddingJobService embeddingJobService,
                          AppSettingService appSettingService) {
        this.difyWorkflowClient = difyWorkflowClient;
        this.contactService = contactService;
        this.messageLogMapper = messageLogMapper;
        this.memoryNoteMapper = memoryNoteMapper;
        this.askTraceMapper = askTraceMapper;
        this.retrievalService = retrievalService;
        this.embeddingJobService = embeddingJobService;
        this.appSettingService = appSettingService;
    }

    @Override
    public AskResponse ask(AskRequest request) {
        long startedAt = System.currentTimeMillis();
        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (question.isEmpty()) {
            throw new BusinessException("问题不能为空");
        }

        // 0. 用户刚回答了「这是谁」：先记下来（source=USER，夜间任务不再覆盖）
        Map<String, Long> clarified = new LinkedHashMap<>();
        ClarifyAnswer clarifyAnswer = request.getClarify();
        if (clarifyAnswer != null && clarifyAnswer.getAlias() != null
                && !clarifyAnswer.getAlias().isBlank()) {
            if (clarifyAnswer.getSenderId() == null) {
                throw new BusinessException("缺少 QQ 号");
            }
            String alias = clarifyAnswer.getAlias().trim();
            contactService.addUser(clarifyAnswer.getSenderId(), alias, clarifyAnswer.getFrom());
            clarified.put(alias, clarifyAnswer.getSenderId());
        }

        // 1. 查询理解：失败就把原问题整句当 query，理解失败不等于放弃检索
        Resolved resolved = resolve(question);

        // 2. 认人：先扫完全部 entity 找歧义——歧义优先于一切，且不进入检索
        Set<Long> subjects = new LinkedHashSet<>();
        String ambiguousAlias = null;
        List<Contact> ambiguousRows = List.of();
        List<String> unknownAliases = new ArrayList<>();
        for (String alias : resolved.entities()) {
            if (clarified.containsKey(alias)) {
                subjects.add(clarified.get(alias));
                continue;
            }
            List<Contact> rows = findByAliasSafely(alias);
            Set<Long> senderIds = new LinkedHashSet<>();
            for (Contact row : rows) {
                if (row.getSenderId() != null) {
                    senderIds.add(row.getSenderId());
                }
            }
            if (senderIds.size() >= 2) {
                if (ambiguousAlias == null) {
                    ambiguousAlias = alias;
                    ambiguousRows = rows;
                }
            } else if (senderIds.size() == 1) {
                subjects.add(senderIds.iterator().next());
            } else {
                if (!unknownAliases.contains(alias)) {
                    unknownAliases.add(alias);
                }
            }
        }

        if (ambiguousAlias != null) {
            // 终态第 1 条：先问清是谁，不猜、不检索
            EmbeddingJobService.EmbeddingStatus status = safeStatus();
            AskScopeVO scope = scope(status, 0, 0, emptyChannels(), "");
            AskResponse response = response("", false, List.of(),
                    clarifyState(ambiguousAlias, ambiguousRows, null), scope);
            writeTrace(question, resolved, scope.getChannels(), 0, 0, "", false, startedAt);
            return response;
        }

        // 3. 未知 alias 一律先算出现次数（跨会话子串计数）
        Map<String, Long> occurrences = new LinkedHashMap<>();
        for (String alias : unknownAliases) {
            occurrences.put(alias, countMentionsSafely(alias));
        }

        // 4. 检索前补跑向量：处理不完的剩余条数如实写进 scope.lag
        try {
            embeddingJobService.indexMissing(preRunIndexLimit);
        } catch (Exception e) {
            log.warn("检索前补跑索引失败，继续检索：{}", e.toString());
        }
        EmbeddingJobService.EmbeddingStatus status = safeStatus();

        // 5. 多轮召回（≤3 轮，含第 1 轮）：每轮检索 → 跨轮累计 → 作答，答不出才 expand
        Map<String, Integer> channels = emptyChannels();
        RetrievalResult cumulative = null;
        String latestTimeHint = resolved.timeHint();
        QueryPlan plan = new QueryPlan(subjects, resolved.entities(), resolved.keywords(),
                resolved.queries(), resolved.timeFrom(), resolved.timeTo());
        int rounds = 0;
        List<Candidate> candidates = List.of();
        String answerText = "";
        List<String> used = List.of();
        boolean answerFailed = false;

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            rounds = round;

            // 5.1 检索：本轮排除累计集里已有的 key（R7）；单轮失败不冒 500
            RetrievalResult fresh = retrieveSafely(plan, keysOf(cumulative));
            if (fresh != null) {
                accumulateChannels(channels, fresh);
                cumulative = cumulative == null ? fresh : cumulative.merge(fresh);
            }

            // 5.2 预算截断只对累计集做一次，且在下一次作答前（R8）；时间软分段用第 1 轮的区间
            candidates = RrfFusion.fuse(cumulative, CHAR_BUDGET, MAX_ITEMS,
                    resolved.timeFrom(), resolved.timeTo());

            // 5.3 作答；失败只降级，不冒 500
            answerText = "";
            used = List.of();
            answerFailed = false;
            if (!candidates.isEmpty()) {
                try {
                    Map<String, Object> outputs = difyWorkflowClient.run("ask", Map.of(
                            "stage", "answer",
                            "question", question,
                            "candidates", renderCandidates(candidates),
                            "time_hint", nullToEmpty(latestTimeHint)));
                    answerText = text(outputs.get("answer")).trim();
                    List<String> parsed = stringsOrNull(outputs.get("used_ids"));
                    used = parsed == null ? List.of() : parsed;
                } catch (Exception e) {
                    answerFailed = true;
                    log.warn("作答失败，降级为证据列表：{}", e.toString());
                }
            }

            // 5.4 有可追溯的结论就收工；否则在还有轮次预算时 expand（R3/R10）
            boolean usable = !answerFailed && !answerText.isEmpty()
                    && !used.isEmpty() && allowedKeys(candidates).containsAll(used);
            if (usable || round == MAX_ROUNDS) {
                break;
            }

            ExpandResult expand = expandSafely(question, candidates, latestTimeHint);
            if (expand == null || (expand.queries().isEmpty() && expand.entities().isEmpty())) {
                // expand 失败或没有新词：再检索也是原地打转
                break;
            }
            if (!expand.timeHint().isBlank()) {
                latestTimeHint = expand.timeHint();
            }
            // R5：只给了实体没给检索串时，用实体当检索串，词法/语义通道才有东西可查
            List<String> roundQueries = expand.queries().isEmpty()
                    ? expand.entities() : expand.queries();
            // R6：expand 发现的人名只进本轮 entities（名字出现通道），不改认人结果、不加 subjects
            plan = new QueryPlan(subjects, expand.entities(), resolved.keywords(), roundQueries,
                    expand.timeFrom(), expand.timeTo());
        }
        int candidateCnt = distinctKeys(cumulative);

        // 7. 证据校验 + 终态表（spec §7.7，优先级从上到下；循环结束后只判定一次）
        Set<String> allowed = new HashSet<>();
        for (Candidate candidate : candidates) {
            allowed.add(candidate.key());
        }
        boolean gaveConclusion = !answerFailed && !answerText.isEmpty();
        boolean usedValid = !used.isEmpty() && allowed.containsAll(used);
        boolean degraded = answerFailed || (gaveConclusion && !usedValid);

        String finalAnswer = "";
        List<EvidenceVO> evidence;
        ClarifyStateVO needClarify = null;
        // §7.8：空串表示无需解释。只有第 4–7 条才给非空文案。
        String scopeMessage = "";

        if (gaveConclusion && usedValid) {
            // 第 2 条：模型给了结论，且证据全部落在候选集内
            finalAnswer = answerText;
            evidence = evidenceOf(used, candidates);
            if (!unknownAliases.isEmpty()) {
                scopeMessage = MESSAGE_UNREGISTERED;
            }
        } else if (degraded) {
            // 第 3 条（作答失败也走这里）：结论不可追溯，只回证据
            evidence = evidence(top(candidates));
        } else {
            String unknownWithHits = firstUnknownWithOccurrences(unknownAliases, occurrences);
            if (unknownWithHits != null) {
                // 第 4 条：记录里出现过，但不知道是谁——反问有意义
                int shown = (int) Math.min(occurrences.get(unknownWithHits), OCCURRENCE_DISPLAY_CAP);
                needClarify = clarifyState(unknownWithHits, List.of(), shown);
                scopeMessage = "记录里出现过 " + shown + " 次，这是谁？";
                evidence = evidence(top(candidates));
            } else if (!unknownAliases.isEmpty()) {
                // 第 5 条：这个名字根本没出现过——反问没有意义
                scopeMessage = "「" + unknownAliases.get(0) + "」没有在记录里出现过";
                evidence = List.of();
            } else if (!candidates.isEmpty()) {
                // 第 6 条：找到相关记录，但没能得出结论
                scopeMessage = MESSAGE_WITH_CANDIDATES;
                evidence = evidence(top(candidates));
            } else {
                // 第 7 条：没有候选
                scopeMessage = MESSAGE_NOT_FOUND;
                evidence = List.of();
            }
        }

        AskScopeVO scope = scope(status, rounds, candidateCnt, channels, scopeMessage);
        AskResponse response = response(finalAnswer, degraded, evidence, needClarify, scope);
        writeTrace(question, resolved, channels, candidateCnt, rounds, finalAnswer, degraded, startedAt);
        return response;
    }

    // ------------------------------------------------------------------ resolve

    private Resolved resolve(String question) {
        Map<String, Object> outputs;
        try {
            outputs = difyWorkflowClient.run("ask", Map.of(
                    "stage", "resolve",
                    "question", question,
                    "known_contacts", knownContactsJson(),
                    "owner_aliases", appSettingService.getOrDefault("owner_aliases", ""),
                    "current_time", currentTime()));
        } catch (Exception e) {
            log.warn("查询理解失败，按原问题检索：{}", e.toString());
            return new Resolved(List.of(), List.of(), List.of(question), "", null, null);
        }
        List<String> entities = strings(outputs.get("entities"));
        List<String> keywords = strings(outputs.get("keywords"));
        List<String> queries = strings(outputs.get("queries")).stream()
                .filter(query -> !query.isBlank())
                .limit(MAX_QUERIES)
                .toList();
        if (queries.isEmpty()) {
            queries = List.of(question);
        }
        LocalDateTime[] range = parseTimeRange(outputs.get("time_from"), outputs.get("time_to"));
        return new Resolved(entities, keywords, queries, text(outputs.get("time_hint")),
                range[0], range[1]);
    }

    /**
     * §7.1：空字段是合法的开区间；但只要有一个字段非空且解析不了，两个都置空。
     * 不能留半开区间——「上周」解析失败却把 end 传下去，会把检索悄悄限制在一个错的区间里。
     */
    private LocalDateTime[] parseTimeRange(Object rawFrom, Object rawTo) {
        String fromText = text(rawFrom).trim();
        String toText = text(rawTo).trim();
        LocalDateTime from = parseTime(rawFrom);
        LocalDateTime to = parseTime(rawTo);
        boolean fromBroken = !fromText.isEmpty() && from == null;
        boolean toBroken = !toText.isEmpty() && to == null;
        if (fromBroken || toBroken) {
            log.warn("时间提示解析失败，按无时间提示处理：time_from={}, time_to={}", fromText, toText);
            return new LocalDateTime[]{null, null};
        }
        return new LocalDateTime[]{from, to};
    }

    private record Resolved(List<String> entities,
                            List<String> keywords,
                            List<String> queries,
                            String timeHint,
                            LocalDateTime timeFrom,
                            LocalDateTime timeTo) {
    }

    // ------------------------------------------------------------------ expand

    /**
     * spec §7.6 / R4：把问题 + 累计候选摘要发给 Dify，换回新检索串与新实体。
     * 解析口径与 resolve 完全一致（queries 截 4 条、时间字段整对置空）。
     * 调用或解析失败都返回 {@code null}：多轮就地结束，绝不冒 500（R11）。
     */
    private ExpandResult expandSafely(String question, List<Candidate> candidates, String timeHint) {
        try {
            Map<String, Object> outputs = difyWorkflowClient.run("ask", Map.of(
                    "stage", "expand",
                    "question", question,
                    "candidates", truncate(renderCandidates(candidates), EXPAND_CHAR_LIMIT),
                    "time_hint", nullToEmpty(timeHint),
                    "current_time", currentTime()));
            List<String> queries = strings(outputs.get("queries")).stream()
                    .filter(query -> !query.isBlank())
                    .limit(MAX_QUERIES)
                    .toList();
            List<String> entities = strings(outputs.get("entities"));
            LocalDateTime[] range = parseTimeRange(outputs.get("time_from"), outputs.get("time_to"));
            return new ExpandResult(queries, entities, text(outputs.get("time_hint")).trim(),
                    range[0], range[1]);
        } catch (Exception e) {
            log.warn("expand 失败，结束多轮检索：{}", e.toString());
            return null;
        }
    }

    private record ExpandResult(List<String> queries,
                                List<String> entities,
                                String timeHint,
                                LocalDateTime timeFrom,
                                LocalDateTime timeTo) {
    }

    /** Dify 用 current_time 把「上周」这类相对说法换算成绝对区间；截到秒，格式固定。 */
    private static String currentTime() {
        return LocalDateTime.now().withNano(0).format(ISO_SECONDS);
    }

    /** Dify 只做格式约定；解析不了就两个都置空，按无时间提示处理，不报错（spec §7.1）。 */
    private static LocalDateTime parseTime(Object raw) {
        String value = text(raw).trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // 继续尝试空格分隔
        }
        try {
            return LocalDateTime.parse(value, SPACE_TIME);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 认人

    private List<Contact> findByAliasSafely(String alias) {
        try {
            List<Contact> rows = contactService.findByAlias(alias);
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("认人查询失败，按未登记联系人处理：{}", e.toString());
            return List.of();
        }
    }

    private long countMentionsSafely(String alias) {
        try {
            return messageLogMapper.countMentions(alias);
        } catch (Exception e) {
            log.warn("统计称呼出现次数失败，按 0 处理：{}", e.toString());
            return 0;
        }
    }

    private static String firstUnknownWithOccurrences(List<String> unknownAliases,
                                                      Map<String, Long> occurrences) {
        for (String alias : unknownAliases) {
            Long count = occurrences.get(alias);
            if (count != null && count > 0) {
                return alias;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 召回结果

    private static Map<String, Integer> emptyChannels() {
        Map<String, Integer> channels = new LinkedHashMap<>();
        for (Channel channel : Channel.values()) {
            channels.put(channel.key(), null);
        }
        return channels;
    }

    /**
     * 逐轮累加通道计数（R9）：某通道只要有一轮跑过，就把各轮非 null 的值相加；
     * 所有轮次都没跑/失败（值全是 null）时保持 null。null 与 0 不能混。
     */
    private static void accumulateChannels(Map<String, Integer> sums, RetrievalResult result) {
        if (result == null || result.hits() == null) {
            return;
        }
        for (Channel channel : Channel.values()) {
            List<Candidate> list = result.hits().get(channel);
            if (list == null) {
                continue;
            }
            Integer previous = sums.get(channel.key());
            sums.put(channel.key(), previous == null ? list.size() : previous + list.size());
        }
    }

    private RetrievalResult retrieveSafely(QueryPlan plan, Set<String> excludeKeys) {
        try {
            return retrievalService.retrieve(plan, excludeKeys);
        } catch (Exception e) {
            log.warn("七路召回失败，按无候选处理：{}", e.toString());
            return null;
        }
    }

    /** 累计集里所有 {@code type:id}，作为下一轮的 excludeKeys。 */
    private static Set<String> keysOf(RetrievalResult result) {
        if (result == null || result.hits() == null) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (List<Candidate> list : result.hits().values()) {
            if (list == null) {
                continue;
            }
            for (Candidate candidate : list) {
                if (candidate != null) {
                    keys.add(candidate.key());
                }
            }
        }
        return keys;
    }

    private static Set<String> allowedKeys(List<Candidate> candidates) {
        Set<String> allowed = new HashSet<>();
        for (Candidate candidate : candidates) {
            allowed.add(candidate.key());
        }
        return allowed;
    }

    /** candidate_cnt：跨通道去重、预算截断前的候选数。 */
    private static int distinctKeys(RetrievalResult result) {
        Map<Channel, List<Candidate>> hits = result == null ? null : result.hits();
        if (hits == null) {
            return 0;
        }
        Set<String> keys = new HashSet<>();
        for (List<Candidate> list : hits.values()) {
            if (list == null) {
                continue;
            }
            for (Candidate candidate : list) {
                if (candidate != null) {
                    keys.add(candidate.key());
                }
            }
        }
        return keys.size();
    }

    private static List<Candidate> top(List<Candidate> candidates) {
        return candidates.subList(0, Math.min(EVIDENCE_LIMIT, candidates.size()));
    }

    private String renderCandidates(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Candidate candidate : candidates) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(candidate.key()).append('|')
                    .append(nullToEmpty(candidate.label())).append('|')
                    .append(nullToEmpty(candidate.content()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 证据

    /** 按 used 的顺序取证据；批量补 note 的 source/quote。 */
    private List<EvidenceVO> evidenceOf(List<String> used, List<Candidate> candidates) {
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            byKey.putIfAbsent(candidate.key(), candidate);
        }
        List<Candidate> selected = new ArrayList<>();
        // 模型偶尔会重复引用同一个 id：去重，避免同一张证据卡渲染两次
        for (String key : new LinkedHashSet<>(used)) {
            Candidate candidate = byKey.get(key);
            if (candidate != null) {
                selected.add(candidate);
            }
        }
        return evidence(selected);
    }

    private List<EvidenceVO> evidence(List<Candidate> selected) {
        if (selected.isEmpty()) {
            return List.of();
        }
        NoteSources sources = loadNoteSources(selected);
        List<EvidenceVO> out = new ArrayList<>(selected.size());
        for (Candidate candidate : selected) {
            out.add(toEvidence(candidate, sources));
        }
        return out;
    }

    /**
     * 一次答题结束后把所有 note 证据的 source_msg_ids 收集起来，用**一次** selectBatchIds
     * 查回 message_log，再在内存里拼出处（spec §7.5）。
     */
    private NoteSources loadNoteSources(List<Candidate> selected) {
        List<Long> noteIds = selected.stream()
                .filter(candidate -> "note".equals(candidate.type()))
                .map(Candidate::id)
                .distinct()
                .toList();
        if (noteIds.isEmpty()) {
            return NoteSources.EMPTY;
        }

        Map<Long, MemoryNote> notes = new HashMap<>();
        try {
            List<MemoryNote> rows = memoryNoteMapper.selectBatchIds(noteIds);
            if (rows != null) {
                for (MemoryNote row : rows) {
                    notes.put(row.getId(), row);
                }
            }
        } catch (Exception e) {
            log.warn("回查记忆来源失败，证据出处留空：{}", e.toString());
            return NoteSources.EMPTY;
        }

        Map<Long, List<Long>> sourceIdsByNote = new LinkedHashMap<>();
        Set<Long> allSourceIds = new LinkedHashSet<>();
        for (Long noteId : noteIds) {
            MemoryNote note = notes.get(noteId);
            List<Long> sourceIds = parseIds(note == null ? null : note.getSourceMsgIds());
            sourceIdsByNote.put(noteId, sourceIds);
            allSourceIds.addAll(sourceIds);
        }
        if (allSourceIds.isEmpty()) {
            return NoteSources.EMPTY;
        }

        Map<Long, MessageLog> messages = new HashMap<>();
        try {
            List<MessageLog> rows = messageLogMapper.selectBatchIds(allSourceIds);
            if (rows != null) {
                for (MessageLog row : rows) {
                    messages.put(row.getId(), row);
                }
            }
        } catch (Exception e) {
            log.warn("回查来源消息失败，证据出处留空：{}", e.toString());
            return NoteSources.EMPTY;
        }

        Map<Long, String> sourceByNote = new HashMap<>();
        Map<Long, String> quoteByNote = new HashMap<>();
        for (Map.Entry<Long, List<Long>> entry : sourceIdsByNote.entrySet()) {
            List<MessageLog> found = new ArrayList<>();
            for (Long id : entry.getValue()) {
                MessageLog row = messages.get(id);
                if (row != null) {
                    found.add(row);
                }
            }
            if (found.isEmpty()) {
                continue;
            }
            sourceByNote.put(entry.getKey(), formatSources(found));
            quoteByNote.put(entry.getKey(),
                    truncate(flatten(found.get(0).getContent()), QUOTE_LIMIT));
        }
        return new NoteSources(sourceByNote, quoteByNote);
    }

    /** 群聊「群名 · 人名 · MM-dd HH:mm」，私聊「QQ 对方QQ · 人名 · MM-dd HH:mm」。 */
    private static String formatSources(List<MessageLog> rows) {
        List<String> parts = new ArrayList<>();
        int shown = Math.min(rows.size(), SOURCE_MAX);
        for (int i = 0; i < shown; i++) {
            parts.add(SourceFormat.messageSource(rows.get(i)));
        }
        if (rows.size() > SOURCE_MAX) {
            // spec §7.5「其余以等 N 条收尾」：N 是剩余条数，不是总数
            parts.add("等 " + (rows.size() - SOURCE_MAX) + " 条");
        }
        return String.join("；", parts);
    }

    private static EvidenceVO toEvidence(Candidate candidate, NoteSources sources) {
        EvidenceVO vo = new EvidenceVO();
        vo.setType(candidate.type());
        vo.setId(String.valueOf(candidate.id()));
        vo.setLabel(nullToEmpty(candidate.label()));
        vo.setContent(nullToEmpty(candidate.content()));
        if ("note".equals(candidate.type())) {
            vo.setSource(sources.source().get(candidate.id()));
            vo.setQuote(sources.quote().get(candidate.id()));
        } else {
            vo.setSource(candidate.source());
            vo.setQuote(candidate.quote());
        }
        return vo;
    }

    private record NoteSources(Map<Long, String> source, Map<Long, String> quote) {
        static final NoteSources EMPTY = new NoteSources(Map.of(), Map.of());
    }

    private static List<Long> parseIds(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // 脏数据跳过，不让证据组装失败
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ 终态与 scope

    private static ClarifyStateVO clarifyState(String alias, List<Contact> rows, Integer occurrences) {
        ClarifyStateVO state = new ClarifyStateVO();
        state.setAlias(alias);
        List<ClarifyCandidateVO> candidates = new ArrayList<>();
        for (Contact row : rows) {
            ClarifyCandidateVO candidate = new ClarifyCandidateVO();
            candidate.setSenderId(row.getSenderId());
            candidate.setAlias(row.getAlias());
            candidate.setRelation(row.getRelation());
            candidate.setHint("QQ " + row.getSenderId());
            candidates.add(candidate);
        }
        state.setCandidates(candidates);
        state.setOccurrences(occurrences);
        return state;
    }

    private static AskScopeVO scope(EmbeddingJobService.EmbeddingStatus status, int rounds,
                                    int candidateCnt, Map<String, Integer> channels, String message) {
        AskScopeVO scope = new AskScopeVO();
        scope.setSemantic(status != null && status.semantic());
        scope.setLag(status == null ? 0 : (int) Math.min(status.lag(), Integer.MAX_VALUE));
        scope.setRounds(rounds);
        scope.setCandidateCnt(candidateCnt);
        scope.setChannels(channels);
        scope.setMessage(message);
        return scope;
    }

    private static AskResponse response(String answer, boolean degraded,
                                        List<EvidenceVO> evidence, ClarifyStateVO needClarify,
                                        AskScopeVO scope) {
        AskResponse response = new AskResponse();
        response.setAnswer(answer == null ? "" : answer);
        response.setDegraded(degraded);
        response.setEvidence(evidence);
        response.setNeedClarify(needClarify);
        response.setScope(scope);
        return response;
    }

    private EmbeddingJobService.EmbeddingStatus safeStatus() {
        try {
            EmbeddingJobService.EmbeddingStatus status = embeddingJobService.status();
            return status == null
                    ? new EmbeddingJobService.EmbeddingStatus(false, 0, 0) : status;
        } catch (Exception e) {
            log.warn("读取向量索引状态失败：{}", e.toString());
            return new EmbeddingJobService.EmbeddingStatus(false, 0, 0);
        }
    }

    // ------------------------------------------------------------------ 轨迹

    private void writeTrace(String question, Resolved resolved, Map<String, Integer> channels,
                            int candidateCnt, int rounds, String answer, boolean degraded,
                            long startedAt) {
        try {
            AskTrace trace = new AskTrace();
            trace.setQuestion(truncate(question, TRACE_QUESTION_LIMIT));
            trace.setEntities(jsonArray(resolved.entities(), TRACE_ENTITIES_LIMIT));
            trace.setQueries(jsonArray(resolved.queries(), TRACE_QUERIES_LIMIT));
            trace.setChannels(hitChannelKeys(channels));
            trace.setCandidateCnt(candidateCnt);
            trace.setRounds(rounds);
            trace.setAnswer(truncate(answer, TRACE_ANSWER_LIMIT));
            trace.setDegraded(degraded);
            trace.setLatencyMs((int) Math.min(System.currentTimeMillis() - startedAt, Integer.MAX_VALUE));
            askTraceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("问答轨迹写入失败：{}", e.toString());
        }
    }

    private static String hitChannelKeys(Map<String, Integer> channels) {
        if (channels == null || channels.isEmpty()) {
            return null;
        }
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : channels.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                keys.add(entry.getKey());
            }
        }
        return keys.isEmpty() ? null : String.join(",", keys);
    }

    private static String jsonArray(List<String> values, int limit) {
        try {
            return truncate(JSON.writeValueAsString(values == null ? List.of() : values), limit);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 杂项

    private String knownContactsJson() {
        List<Contact> known = contactService.list(new LambdaQueryWrapper<Contact>()
                .orderByDesc(Contact::getUpdatedAt)
                .last("LIMIT " + KNOWN_CONTACTS_LIMIT));
        List<Map<String, String>> rows = known.stream().map(c -> Map.of(
                "senderId", String.valueOf(c.getSenderId()),
                "alias", nullToEmpty(c.getAlias()),
                "relation", nullToEmpty(c.getRelation()))).toList();
        try {
            return JSON.writeValueAsString(rows);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 字符串数组；形状不对就当没认出来人。 */
    private List<String> strings(Object raw) {
        List<String> parsed = stringsOrNull(raw);
        return parsed == null ? List.of() : parsed;
    }

    private List<String> stringsOrNull(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object o : collection) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().trim());
                }
            }
            return out;
        }
        String value = raw.toString().trim();
        if (value.isEmpty()) {
            return List.of();
        }
        try {
            List<Object> values = JSON.readValue(value, new TypeReference<List<Object>>() {
            });
            for (Object item : values) {
                if (item != null && !item.toString().isBlank()) {
                    out.add(item.toString().trim());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Dify 返回的字符串数组不合法：{}", value);
            return null;
        }
    }

    private static String text(Object raw) {
        return raw == null ? "" : raw.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String flatten(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
