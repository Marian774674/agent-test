package com.czx.qqmessageassistantjava.retrieval;

import com.czx.qqmessageassistantjava.embedding.EmbeddingIndex;
import com.czx.qqmessageassistantjava.embedding.EmbeddingService;
import com.czx.qqmessageassistantjava.entity.FileTask;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoryNote;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.FileTaskMapper;
import com.czx.qqmessageassistantjava.mapper.MemoMapper;
import com.czx.qqmessageassistantjava.mapper.MemoryNoteMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 七路召回实现（spec §7.3）：{@code note / msg-person / msg-mention / msg-lexical /
 * msg-semantic / memo / file}。
 *
 * <p>三条不可退让的规则：
 * <ul>
 *   <li><b>不按会话或发送人过滤</b>：除了 {@code msg-person} 按主体 sender 查，
 *       其余通道都是全库的——答案可以在任何人说的任何会话里；</li>
 *   <li><b>单路失败只丢这一路</b>：抛异常的通道不出现在 {@code hits} 里并记 WARN，
 *       其余通道照常返回，绝不让一次 SQL 错误冒泡成 500；</li>
 *   <li><b>null 与空列表不是一回事</b>：跑了的通道一定带 key（可能是空列表），
 *       没跑/不可用/报错的通道才没有 key。</li>
 * </ul>
 */
@Service
public class RetrievalServiceImpl implements RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalServiceImpl.class);

    /** spec §7.3 的每路上限。 */
    static final int NOTE_SUBJECT_LIMIT = 200;
    static final int NOTE_KEYWORD_LIMIT = 100;
    static final int MSG_PERSON_LIMIT = 200;
    static final int MSG_MENTION_LIMIT = 100;
    static final int MSG_LEXICAL_LIMIT = 100;
    static final int MSG_SEMANTIC_LIMIT = 100;
    static final int MEMO_LIMIT = 50;
    static final int FILE_LIMIT = 30;

    /** 语义查询向量只取前 3 条 query（原问题 + 最多 2 条改写）。 */
    static final int QUERY_VECTOR_COUNT = 3;

    /** 证据原文的截断长度。 */
    static final int QUOTE_LIMIT = 200;

    /**
     * 为「排除已有候选」而超额取数的硬上限。多轮时 {@code excludeKeys} 可能很大，
     * 但不能因此把整张表拉进内存。
     */
    static final int MAX_FETCH_LIMIT = 2000;

    private final MemoryNoteMapper memoryNoteMapper;
    private final MessageLogMapper messageLogMapper;
    private final MemoMapper memoMapper;
    private final FileTaskMapper fileTaskMapper;
    private final EmbeddingService embeddingService;
    private final EmbeddingIndex embeddingIndex;

    public RetrievalServiceImpl(MemoryNoteMapper memoryNoteMapper,
                                MessageLogMapper messageLogMapper,
                                MemoMapper memoMapper,
                                FileTaskMapper fileTaskMapper,
                                EmbeddingService embeddingService,
                                EmbeddingIndex embeddingIndex) {
        this.memoryNoteMapper = memoryNoteMapper;
        this.messageLogMapper = messageLogMapper;
        this.memoMapper = memoMapper;
        this.fileTaskMapper = fileTaskMapper;
        this.embeddingService = embeddingService;
        this.embeddingIndex = embeddingIndex;
    }

    @Override
    public RetrievalResult retrieve(QueryPlan plan, Set<String> excludeKeys) {
        QueryPlan safePlan = plan == null
                ? new QueryPlan(null, null, null, null, null, null)
                : plan;
        Set<String> excluded = excludeKeys == null ? Set.of() : excludeKeys;

        Map<Channel, List<Candidate>> hits = new LinkedHashMap<>();
        collect(hits, Channel.NOTE, () -> noteChannel(safePlan, excluded));
        collect(hits, Channel.MSG_PERSON, () -> personChannel(safePlan, excluded));
        collect(hits, Channel.MSG_MENTION, () -> mentionChannel(safePlan, excluded));
        collect(hits, Channel.MSG_LEXICAL, () -> lexicalChannel(safePlan, excluded));
        if (semanticAvailable()) {
            collect(hits, Channel.MSG_SEMANTIC, () -> semanticChannel(safePlan, excluded));
        }
        collect(hits, Channel.MEMO, () -> memoChannel(safePlan, excluded));
        collect(hits, Channel.FILE, () -> fileChannel(safePlan, excluded));
        return new RetrievalResult(Collections.unmodifiableMap(hits));
    }

    /**
     * 跑一路并放进结果；异常只影响这一路。日志不打印正文明细，只带通道与异常。
     */
    private void collect(Map<Channel, List<Candidate>> hits, Channel channel,
                         Supplier<List<Candidate>> query) {
        try {
            hits.put(channel, List.copyOf(query.get()));
        } catch (Exception e) {
            log.warn("召回通道 {} 失败，已跳过：{}", channel.key(), e.toString());
        }
    }

    private boolean semanticAvailable() {
        try {
            return embeddingService.available();
        } catch (Exception e) {
            log.warn("语义通道可用性检查失败，按不可用处理：{}", e.toString());
            return false;
        }
    }

    /**
     * note 通道：主体（≤200）与关键词 LIKE（≤100）合并去重。
     * 两条来源命中同一条 note 只保留一次，名次取先出现的位置。
     */
    private List<Candidate> noteChannel(QueryPlan plan, Set<String> excluded) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (!plan.subjects().isEmpty()) {
            append(out, seen, excluded,
                    memoryNoteMapper.selectBySubjects(
                            plan.subjects(), fetchLimit(NOTE_SUBJECT_LIMIT, excluded)),
                    this::noteCandidate, NOTE_SUBJECT_LIMIT, true);
        }
        if (!plan.keywords().isEmpty()) {
            append(out, seen, excluded,
                    memoryNoteMapper.searchByKeywords(
                            plan.keywords(), fetchLimit(NOTE_KEYWORD_LIMIT, excluded)),
                    this::noteCandidate, NOTE_KEYWORD_LIMIT, true);
        }
        return out;
    }

    /** msg-person：主体本人发的 IN 消息，时间倒序，≤200。 */
    private List<Candidate> personChannel(QueryPlan plan, Set<String> excluded) {
        if (plan.subjects().isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        append(out, new HashSet<>(), excluded,
                messageLogMapper.selectBySenders(
                        plan.subjects(), fetchLimit(MSG_PERSON_LIMIT, excluded)),
                this::messageCandidate, MSG_PERSON_LIMIT, true);
        return out;
    }

    /**
     * msg-mention：每个 entity 走一次全文，结果顺序拼接。
     *
     * <p><b>不去重</b>：spec §7.3 的计数口径是「按查询串累加、去重前」，
     * 同一个 key 被多个 alias 命中就该计多次；RRF 的 {@code collectScores}
     * 自会按通道内最佳名次处理重复。
     */
    private List<Candidate> mentionChannel(QueryPlan plan, Set<String> excluded) {
        List<Candidate> out = new ArrayList<>();
        for (String entity : plan.entities()) {
            if (isBlank(entity)) {
                continue;
            }
            List<Candidate> batch = new ArrayList<>();
            append(batch, new HashSet<>(), excluded,
                    messageLogMapper.searchFulltext(
                            entity.trim(), fetchLimit(MSG_MENTION_LIMIT, excluded)),
                    this::messageCandidate, MSG_MENTION_LIMIT, false);
            out.addAll(batch);
        }
        return out;
    }

    /**
     * msg-lexical：每条 query 各 ≤100，结果顺序拼接。
     * 与 mention 同理不去重——计数要保留「按查询串累加」的原始命中数。
     */
    private List<Candidate> lexicalChannel(QueryPlan plan, Set<String> excluded) {
        List<Candidate> out = new ArrayList<>();
        for (String query : plan.queries()) {
            if (isBlank(query)) {
                continue;
            }
            List<Candidate> batch = new ArrayList<>();
            append(batch, new HashSet<>(), excluded,
                    messageLogMapper.searchFulltext(
                            query.trim(), fetchLimit(MSG_LEXICAL_LIMIT, excluded)),
                    this::messageCandidate, MSG_LEXICAL_LIMIT, false);
            out.addAll(batch);
        }
        return out;
    }

    /**
     * msg-semantic：前 3 条 query 的向量逐元素平均后重新归一化，只扫一次索引（≤100）。
     * 索引给的是 id 与相似度；按搜索顺序回查 message_log，不能直接用数据库返回顺序。
     * 空内容没有可检索的东西，跳过。
     */
    private List<Candidate> semanticChannel(QueryPlan plan, Set<String> excluded) {
        List<String> queries = plan.queries().stream()
                .filter(query -> !isBlank(query))
                .map(String::trim)
                .limit(QUERY_VECTOR_COUNT)
                .toList();
        if (queries.isEmpty()) {
            return List.of();
        }

        float[] queryVector = averageVector(queries);
        if (queryVector == null) {
            return List.of();
        }

        // 超额取数：已见候选可能占满 top-100，过滤后还要能凑够 100 条新的
        List<EmbeddingIndex.ScoredId> scored = embeddingIndex.search(
                queryVector, fetchLimit(MSG_SEMANTIC_LIMIT, excluded));
        if (scored == null || scored.isEmpty()) {
            return List.of();
        }

        List<Long> ids = scored.stream()
                .map(EmbeddingIndex.ScoredId::messageId)
                .distinct()
                .toList();
        Map<Long, MessageLog> byId = new HashMap<>();
        List<MessageLog> rows = messageLogMapper.selectBatchIds(ids);
        if (rows != null) {
            for (MessageLog row : rows) {
                byId.put(row.getId(), row);
            }
        }

        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EmbeddingIndex.ScoredId hit : scored) {
            if (out.size() >= MSG_SEMANTIC_LIMIT) {
                break;
            }
            MessageLog row = byId.get(hit.messageId());
            if (row == null || isBlank(row.getContent())) {
                continue;
            }
            Candidate candidate = messageCandidate(row);
            String key = candidate.key();
            if (excluded.contains(key) || !seen.add(key)) {
                continue;
            }
            out.add(candidate);
        }
        return out;
    }

    /** memo：标题/正文任一 keyword LIKE，≤50。 */
    private List<Candidate> memoChannel(QueryPlan plan, Set<String> excluded) {
        if (plan.keywords().isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        append(out, new HashSet<>(), excluded,
                memoMapper.searchByKeywords(plan.keywords(), fetchLimit(MEMO_LIMIT, excluded)),
                this::memoCandidate, MEMO_LIMIT, true);
        return out;
    }

    /** file：文件名任一 keyword LIKE，≤30。 */
    private List<Candidate> fileChannel(QueryPlan plan, Set<String> excluded) {
        if (plan.keywords().isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        append(out, new HashSet<>(), excluded,
                fileTaskMapper.searchByKeywords(plan.keywords(), fetchLimit(FILE_LIMIT, excluded)),
                this::fileCandidate, FILE_LIMIT, true);
        return out;
    }

    /**
     * 前 3 条 query 向量逐元素平均，再按 L2 重新归一化。
     * 平均而不是取最大相似度：原问题始终占一半以上权重，避免某条改写把检索带偏。
     */
    private float[] averageVector(List<String> queries) {
        List<float[]> vectors = new ArrayList<>();
        int dim = -1;
        for (String query : queries) {
            float[] vector = embeddingService.embed(query);
            if (vector == null || vector.length == 0) {
                continue;
            }
            if (dim < 0) {
                dim = vector.length;
            } else if (vector.length != dim) {
                throw new IllegalStateException("query vector dimension mismatch");
            }
            vectors.add(vector);
        }
        if (vectors.isEmpty()) {
            return null;
        }

        double[] sum = new double[dim];
        for (float[] vector : vectors) {
            for (int i = 0; i < dim; i++) {
                sum[i] += vector[i];
            }
        }
        double norm = 0;
        for (double value : sum) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return null;
        }
        float[] average = new float[dim];
        for (int i = 0; i < dim; i++) {
            average[i] = (float) (sum[i] / norm);
        }
        return average;
    }

    private Candidate messageCandidate(MessageLog row) {
        String chatNumber = row.getChatId() != null
                ? String.valueOf(row.getChatId())
                : row.getSenderId() != null ? String.valueOf(row.getSenderId()) : "";
        String chatName = blankToNull(row.getChatName());
        if (chatName == null) {
            chatName = blankToNull(row.getSenderName());
        }
        String label = "QQ " + chatNumber + (chatName == null ? "" : " · " + chatName);
        String source = SourceFormat.messageSource(row);
        String quote = truncate(flatten(row.getContent()), QUOTE_LIMIT);
        return new Candidate("msg", row.getId(), label, quote, row.getCreatedAt(), source, quote);
    }

    private Candidate noteCandidate(MemoryNote note) {
        String content = truncate(flatten(note.getValue()), QUOTE_LIMIT);
        return new Candidate(
                "note",
                note.getId(),
                "记忆 · " + nullToEmpty(note.getPredicate()),
                content,
                note.getHappenedAt(),
                null,
                content);
    }

    private Candidate memoCandidate(Memo memo) {
        String content = truncate(flatten(memo.getContent()), QUOTE_LIMIT);
        return new Candidate(
                "memo",
                memo.getId(),
                "备忘 · " + nullToEmpty(memo.getTitle()),
                content,
                memo.getCreatedAt(),
                null,
                content);
    }

    private Candidate fileCandidate(FileTask file) {
        String content = truncate(flatten(file.getRequest()), QUOTE_LIMIT);
        return new Candidate(
                "file",
                file.getId(),
                "文件 · " + nullToEmpty(file.getFileName()),
                content,
                file.getCreatedAt(),
                null,
                content);
    }

    /** 超额取数上限：通道上限 + 已见候选数，封顶 {@link #MAX_FETCH_LIMIT}。 */
    private static int fetchLimit(int channelLimit, Set<String> excluded) {
        long wanted = (long) channelLimit + (excluded == null ? 0 : excluded.size());
        return (int) Math.min(wanted, MAX_FETCH_LIMIT);
    }

    /**
     * 过滤 excluded、按需去重，并在<b>过滤之后</b>按通道上限截断。
     * 必须先超额取数再截断：否则上一轮见过的候选占满 SQL LIMIT 时，
     * 这一轮会误报「通道跑了但为空」，更旧的未命中候选永远取不到。
     */
    private <T> void append(List<Candidate> out, Set<String> seen, Set<String> excluded,
                            List<T> rows, Function<T, Candidate> mapper,
                            int limit, boolean dedupe) {
        if (rows == null) {
            return;
        }
        int added = 0;
        for (T row : rows) {
            if (added >= limit) {
                break;
            }
            Candidate candidate = mapper.apply(row);
            if (candidate == null || candidate.id() <= 0) {
                continue;
            }
            String key = candidate.key();
            if (excluded.contains(key)) {
                continue;
            }
            if (dedupe && !seen.add(key)) {
                continue;
            }
            out.add(candidate);
            added++;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
}
