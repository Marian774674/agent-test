package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.client.DifyWorkflowClient;
import com.czx.qqmessageassistantjava.entity.Contact;
import com.czx.qqmessageassistantjava.entity.MemoryNote;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MemoryNoteMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.ContactService;
import com.czx.qqmessageassistantjava.service.MemoryJobService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 夜间归纳：把游标之后的一段消息交给 Dify，抽成「谁 · 什么属性 · 什么值」。
 *
 * <p>模型只负责语义；证据是否在本批、值是否重复、游标走不走，全部由 Java 判断。
 * 失败时游标不动 —— 下次从这里继续，已写的靠去重挡住。
 */
@Service
public class MemoryJobServiceImpl implements MemoryJobService {

    private static final Logger log = LoggerFactory.getLogger(MemoryJobServiceImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    static final String ENABLED_KEY = "memory_job_enabled";
    static final String CURSOR_KEY = "memory_job_cursor";
    /** 一批最多送这么多条；多了下一轮继续 */
    static final int BATCH_LIMIT = 200;
    static final int CONTENT_LIMIT = 200;
    static final int KNOWN_CONTACTS_LIMIT = 200;
    static final int VALUE_LIMIT = 255;
    /** 全量补算最多跑这么多批；个人库远用不到，挡住「游标没动却死循环」 */
    static final int MAX_REBUILD_BATCHES = 50;

    /** 受控词表：谓词自由发挥时「生日/出生日期/哪天生的」会分裂成三条，查的时候对不上 */
    static final Set<String> PREDICATES = Set.of(
            "生日", "姓名", "专业", "班级", "学号", "住址", "电话", "时间安排", "偏好", "关系", "其他");

    private final MessageLogMapper messageLogMapper;
    private final MemoryNoteMapper memoryNoteMapper;
    private final ContactService contactService;
    private final DifyWorkflowClient difyWorkflowClient;
    private final AppSettingService appSettingService;
    private final TransactionTemplate transactionTemplate;

    public MemoryJobServiceImpl(MessageLogMapper messageLogMapper,
                                MemoryNoteMapper memoryNoteMapper,
                                ContactService contactService,
                                DifyWorkflowClient difyWorkflowClient,
                                AppSettingService appSettingService,
                                TransactionTemplate transactionTemplate) {
        this.messageLogMapper = messageLogMapper;
        this.memoryNoteMapper = memoryNoteMapper;
        this.contactService = contactService;
        this.difyWorkflowClient = difyWorkflowClient;
        this.appSettingService = appSettingService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int runOnce() {
        if (!"1".equals(appSettingService.getOrDefault(ENABLED_KEY, "0").trim())) {
            return 0;
        }
        long cursor = cursor();
        List<MessageLog> batch = messageLogMapper.selectList(new LambdaQueryWrapper<MessageLog>()
                .gt(MessageLog::getId, cursor)
                .eq(MessageLog::getDirection, "IN")
                .orderByAsc(MessageLog::getId)
                .last("LIMIT " + BATCH_LIMIT));
        if (batch.isEmpty()) {
            return 0;
        }

        Set<Long> messageIds = batch.stream().map(MessageLog::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> senders = batch.stream().map(MessageLog::getSenderId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 本批 known_contacts 的快照：判定归因时只能用「送给 Dify 的这一份」，
        // 本批 upsert 出来的联系人不能反过来授权同一批 note（spec §6）。
        List<Contact> knownContacts = knownContacts();
        Map<String, Object> outputs = difyWorkflowClient.run("extract", Map.of(
                "messages", renderMessages(batch),
                "known_contacts", knownContactsJson(knownContacts),
                "owner_aliases", appSettingService.getOrDefault("owner_aliases", "")));

        List<Map<String, Object>> contacts = parseArray(outputs.get("contacts"));
        List<Map<String, Object>> notes = parseArray(outputs.get("notes"));
        if (contacts == null || notes == null) {
            // 形状不对就别推进游标：这一批下次还会重来，宁可重复一次也不丢一段。
            // 能导致这种情况的只有两类：Dify 应用没重新导入发布（end 节点还是旧输出名），
            // 或者模型吐了非 JSON。日志要能直接告诉人排查哪一条。
            log.error("extract 返回的结构不合法（contacts={} notes={}），游标停在 {}，"
                            + "批次 {} 条 id {}..{} 下次会重跑；"
                            + "若反复出现，先检查 Dify「记忆与整理」应用是否已按最新 DSL 重新导入并发布（end 输出名 contacts/notes）",
                    contacts == null ? "bad" : "ok", notes == null ? "bad" : "ok", cursor,
                    batch.size(), messageIds.iterator().next(),
                    messageIds.stream().max(Long::compareTo).orElse(cursor));
            return 0;
        }

        long maxId = messageIds.stream().mapToLong(Long::longValue).max().orElse(cursor);
        AtomicInteger written = new AtomicInteger();
        transactionTemplate.executeWithoutResult(status -> {
            written.set(write(contacts, notes, senders, messageIds, batch, knownContacts));
            appSettingService.putAll(Map.of(CURSOR_KEY, String.valueOf(maxId)));
        });
        log.info("夜间归纳：消息 {} 条 → contact {} 个 / note {} 条，游标 → {}",
                batch.size(), contacts.size(), written.get(), maxId);
        return written.get();
    }

    /**
     * 游标归零后一批批跑完整个历史。
     *
     * <p>用「游标有没有前进」判定还有没有料，而不是用返回条数：一批消息完全可能产出 0 条事实，
     * 那不代表跑完了，直接 break 会把后面的历史永远留在原地。
     */
    @Override
    public int rebuild() {
        appSettingService.putAll(Map.of(CURSOR_KEY, "0"));
        int total = 0;
        for (int i = 0; i < MAX_REBUILD_BATCHES; i++) {
            long before = cursor();
            total += runOnce();
            if (cursor() <= before) {
                break;
            }
        }
        log.info("全量补算结束：累计 {} 条事实", total);
        return total;
    }

    private int write(List<Map<String, Object>> contacts,
                      List<Map<String, Object>> notes,
                      Set<Long> senders,
                      Set<Long> messageIds,
                      List<MessageLog> batch,
                      List<Contact> knownContacts) {
        int contactCount = 0;
        for (Map<String, Object> raw : contacts) {
            Long senderId = toLong(raw.get("sender_id"));
            String alias = truncate(str(raw.get("alias")), 64);
            // 认出来的人必须真的在这批消息里说过话，否则就是模型在编
            if (senderId == null || !senders.contains(senderId) || alias.isBlank()) {
                log.warn("丢弃一条 contact：sender={} alias={} 不在本批内", senderId, alias);
                continue;
            }
            contactService.upsertAuto(senderId, alias, truncate(str(raw.get("relation")), 64));
            contactCount++;
        }

        // 证据正文按下标缓存：归因校验要求「alias 出现在该 note 自己的证据里」。
        Map<Long, String> contentById = batch.stream()
                .filter(m -> m.getId() != null)
                .collect(Collectors.toMap(MessageLog::getId,
                        m -> nullToEmpty(m.getContent()), (first, second) -> first));
        // 证据作者：主体亲自写了这条证据，才算他名下的第一人称事实
        Map<Long, Long> senderById = batch.stream()
                .filter(m -> m.getId() != null && m.getSenderId() != null)
                .collect(Collectors.toMap(MessageLog::getId, MessageLog::getSenderId,
                        (first, second) -> first));
        // 只用送给 Dify 的那份 known_contacts 快照；一人可能有多个 alias
        Map<Long, List<String>> aliasesBySender = knownContacts.stream()
                .filter(c -> c.getSenderId() != null && c.getAlias() != null && !c.getAlias().isBlank())
                .collect(Collectors.groupingBy(Contact::getSenderId,
                        Collectors.mapping(Contact::getAlias, Collectors.toList())));

        List<MemoryNote> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> raw : notes) {
            Long subject = toLong(raw.get("subject_sender_id"));
            String value = truncate(str(raw.get("value")), VALUE_LIMIT);
            List<Long> evidence = idsOf(raw.get("source_msg_ids"));
            if (value.isBlank() || evidence.isEmpty() || !messageIds.containsAll(evidence)) {
                log.warn("丢弃一条 note：value={} 证据 {} 不在本批内", value, evidence);
                continue;
            }
            // 归因闸门（spec §6）：主体亲自写了证据放行；否则必须是 known_contacts 里的人，
            // 且其任一 alias 出现在本条 note 的证据正文中。防「小红的事记到小明头上」。
            if (subject != null && !attributionAllowed(subject, aliasesBySender,
                    evidence, contentById, senderById)) {
                log.info("丢弃一条 note：主体 {} 的归因不成立（未亲自写证据 {}，也不在 known_contacts 或其 alias 未出现在正文中）",
                        subject, evidence);
                continue;
            }
            String predicate = predicate(str(raw.get("predicate")));
            String key = subject + "|" + predicate + "|" + value;
            if (!seen.add(key) || alreadyExists(subject, predicate, value)) {
                continue;
            }
            MemoryNote row = new MemoryNote();
            row.setSubjectSenderId(subject);
            row.setPredicate(predicate);
            row.setValue(value);
            row.setHappenedAt(date(raw.get("happened_at")));
            row.setSourceMsgIds(evidence.stream().map(String::valueOf).collect(Collectors.joining(",")));
            row.setConfidence(confidence(raw.get("confidence")));
            // 与 message_log 同一个钟，别用库里的 UTC 默认值
            row.setCreatedAt(LocalDateTime.now());
            rows.add(row);
        }
        rows.forEach(memoryNoteMapper::insert);
        return rows.size();
    }

    /**
     * 主体是否可以归因（spec §6）：
     * <ol>
     *   <li>主体本人写了本条 note 的证据消息 → 放行（第一人称的事实里不会出现自己的名字）；</li>
     *   <li>否则主体必须是 known_contacts 快照里的人，且其任一 alias 在本条 note 的证据正文里出现过。</li>
     * </ol>
     * 只拼「本条 note 自己的」证据正文：别的 note 的证据不能拿来授权。
     *
     * <p><b>「主体在本批说过话」本身不授权。</b>群聊一批两百条消息里几乎人人都是发言人，
     * 若只按「在本批发言」放行，模型把小红说的「我生日 11 月 11 日」记到同批的小明头上时，
     * 闸门会形同虚设——而这正是本闸门要防的那一类错误。要求「主体亲自写了这条证据」
     * 才同时满足第一人称与防错配两个目标。
     */
    private static boolean attributionAllowed(Long subject,
                                             Map<Long, List<String>> aliasesBySender,
                                             List<Long> evidence,
                                             Map<Long, String> contentById,
                                             Map<Long, Long> senderById) {
        boolean authored = evidence.stream()
                .anyMatch(id -> subject.equals(senderById.get(id)));
        if (authored) {
            return true;
        }
        List<String> aliases = aliasesBySender.get(subject);
        if (aliases == null || aliases.isEmpty()) {
            return false;
        }
        String content = evidence.stream()
                .map(id -> contentById.getOrDefault(id, ""))
                .collect(Collectors.joining("\n"));
        for (String alias : aliases) {
            if (aliasAppearsIn(alias, content)) {
                return true;
            }
        }
        return false;
    }

    /** 大小写不敏感的子串判断；alias / 正文为空时不成立。包内可见，供测试。 */
    static boolean aliasAppearsIn(String alias, String content) {
        if (alias == null || alias.isBlank() || content == null) {
            return false;
        }
        return content.toLowerCase(Locale.ROOT).contains(alias.trim().toLowerCase(Locale.ROOT));
    }

    private boolean alreadyExists(Long subject, String predicate, String value) {
        LambdaQueryWrapper<MemoryNote> query = new LambdaQueryWrapper<MemoryNote>()
                .eq(MemoryNote::getPredicate, predicate)
                .eq(MemoryNote::getValue, value);
        if (subject == null) {
            query.isNull(MemoryNote::getSubjectSenderId);
        } else {
            query.eq(MemoryNote::getSubjectSenderId, subject);
        }
        return memoryNoteMapper.selectCount(query) > 0;
    }

    private String renderMessages(List<MessageLog> batch) {
        StringBuilder sb = new StringBuilder();
        for (MessageLog m : batch) {
            String name = nullToEmpty(m.getSenderName()).isBlank()
                    ? String.valueOf(m.getSenderId()) : m.getSenderName();
            String chat = nullToEmpty(m.getChatName()).isBlank() ? "-" : m.getChatName();
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(m.getId()).append('|')
                    .append(m.getSenderId()).append('|')
                    .append(name).append('|')
                    .append(chat).append('|')
                    .append(m.getCreatedAt() == null ? "" : HH_MM.format(m.getCreatedAt())).append('|')
                    .append(truncate(flatten(m.getContent()), CONTENT_LIMIT));
        }
        return sb.toString();
    }

    /** 送给 Dify 的 known_contacts；调用方保存这份快照，归因校验必须用同一份。 */
    private List<Contact> knownContacts() {
        return contactService.list(new LambdaQueryWrapper<Contact>()
                .orderByDesc(Contact::getUpdatedAt)
                .last("LIMIT " + KNOWN_CONTACTS_LIMIT));
    }

    private String knownContactsJson(List<Contact> known) {
        List<Map<String, Object>> rows = known.stream().map(c -> Map.<String, Object>of(
                "senderId", String.valueOf(c.getSenderId()),
                "alias", nullToEmpty(c.getAlias()),
                "relation", nullToEmpty(c.getRelation()))).toList();
        try {
            return JSON.writeValueAsString(rows);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 返回 null=解析失败（游标不前进），空列表=模型明确说没有 */
    private List<Map<String, Object>> parseArray(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Collection<?> collection) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object o : collection) {
                if (o instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    rows.add(typed);
                }
            }
            return rows;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return List.of();
        }
        try {
            return JSON.readValue(text, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("Dify 返回的数组不是合法 JSON：{}", text, e);
            return null;
        }
    }

    private List<Long> idsOf(Object raw) {
        List<Long> ids = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object o : collection) {
                Long id = toLong(o);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private String predicate(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        return PREDICATES.contains(trimmed) ? trimmed : "其他";
    }

    /**
     * 模型给的 {@code happened_at} 是「这件事发生在哪天」，统一按当天 00:00 存。
     *
     * <p>空串、乱格式一律当没给：宁可丢掉时间精度，也不要把一条事实整条扔掉。
     */
    private LocalDateTime date(Object raw) {
        String trimmed = str(raw).trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed)
                    .atStartOfDay();
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("happened_at 不是合法日期：{}", trimmed);
            return null;
        }
    }

    private BigDecimal confidence(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.toString().trim());
            if (value.compareTo(BigDecimal.ZERO) < 0) {
                return BigDecimal.ZERO;
            }
            return value.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long cursor() {
        String raw = appSettingService.getOrDefault(CURSOR_KEY, "0").trim();
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Long toLong(Object o) {
        if (o instanceof Number number) {
            return number.longValue();
        }
        try {
            return o == null ? null : Long.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String flatten(String content) {
        return content == null ? "" : content.replaceAll("[\\r\\n]+", " ").trim();
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private String str(Object raw) {
        return raw == null ? "" : raw.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
