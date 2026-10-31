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

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("MM-dd HH:mm");

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
            List<MemoryNote> bySubject = memoryNoteMapper.selectBySubjects(
                    plan.subjects(), NOTE_SUBJECT_LIMIT);
            if (bySubject != null) {
                for (MemoryNote note : bySubject) {
                    add(out, seen, excluded, noteCandidate(note));
                }
            }
        }
        if (!plan.keywords().isEmpty()) {
            List<MemoryNote> byKeyword = memoryNoteMapper.searchByKeywords(
                    plan.keywords(), NOTE_KEYWORD_LIMIT);
            if (byKeyword != null) {
                for (MemoryNote note : byKeyword) {
                    add(out, seen, excluded, noteCandidate(note));
                }
            }
        }
        return out;
    }

    /** msg-person：主体本人发的 IN 消息，时间倒序，≤200。 */
    private List<Candidate> personChannel(QueryPlan plan, Set<String> excluded) {
        if (plan.subjects().isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<MessageLog> rows = messageLogMapper.selectBySenders(
                plan.subjects(), MSG_PERSON_LIMIT);
        if (rows != null) {
            for (MessageLog row : rows) {
                add(out, seen, excluded, messageCandidate(row));
            }
        }
        return out;
    }

    /** msg-mention：每个 entity 走一次全文，结果顺序拼接，同一消息只留首次。 */
    private List<Candidate> mentionChannel(QueryPlan plan, Set<String> excluded) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entity : plan.entities()) {
            if (isBlank(entity)) {
                continue;
            }
            List<MessageLog> rows = messageLogMapper.searchFulltext(
                    entity.trim(), MSG_MENTION_LIMIT);
            if (rows != null) {
                for (MessageLog row : rows) {
                    add(out, seen, excluded, messageCandidate(row));
                }
            }
        }
        return out;
    }

    /** msg-lexical：每条 query 各 ≤100，结果顺序拼接。 */
    private List<Candidate> lexicalChannel(QueryPlan plan, Set<String> excluded) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String query : plan.queries()) {
            if (isBlank(query)) {
                continue;
            }
            List<MessageLog> rows = messageLogMapper.searchFulltext(
                    query.trim(), MSG_LEXICAL_LIMIT);
            if (rows != null) {
                for (MessageLog row : rows) {
                    add(out, seen, excluded, messageCandidate(row));
                }
            }
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

        List<EmbeddingIndex.ScoredId> scored = embeddingIndex.search(
                queryVector, MSG_SEMANTIC_LIMIT);
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
            MessageLog row = byId.get(hit.messageId());
            if (row == null || isBlank(row.getContent())) {
                continue;
            }
            add(out, seen, excluded, messageCandidate(row));
        }
        return out;
    }

    /** memo：标题/正文任一 keyword LIKE，≤50。 */
    private List<Candidate> memoChannel(QueryPlan plan, Set<String> excluded) {
        if (plan.keywords().isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Memo> rows = memoMapper.searchByKeywords(plan.keywords(), MEMO_LIMIT);
        if (rows != null) {
            for (Memo memo : rows) {
                String content = truncate(flatten(memo.getContent()), QUOTE_LIMIT);
                add(out, seen, excluded, new Candidate(
                        "memo",
                        memo.getId(),
                        "备忘 · " + nullToEmpty(memo.getTitle()),
                        content,
                        memo.getCreatedAt(),
                        null,
                        content));
            }
        }
        return out;
    }

    /** file：文件名任一 keyword LIKE，≤30。 */
    private List<Candidate> fileChannel(QueryPlan plan, Set<String> excluded) {
        if (plan.keywords().isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<FileTask> rows = fileTaskMapper.searchByKeywords(plan.keywords(), FILE_LIMIT);
        if (rows != null) {
            for (FileTask file : rows) {
                String content = truncate(flatten(file.getRequest()), QUOTE_LIMIT);
                add(out, seen, excluded, new Candidate(
                        "file",
                        file.getId(),
                        "文件 · " + nullToEmpty(file.getFileName()),
                        content,
                        file.getCreatedAt(),
                        null,
                        content));
            }
        }
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
        String when = row.getCreatedAt() == null ? "" : MM_DD.format(row.getCreatedAt());
        String source = when.isEmpty() ? label : label + " · " + when;
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

    private void add(List<Candidate> out, Set<String> seen, Set<String> excluded,
                     Candidate candidate) {
        if (candidate == null || candidate.id() <= 0) {
            return;
        }
        String key = candidate.key();
        if (excluded.contains(key) || !seen.add(key)) {
            return;
        }
        out.add(candidate);
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
