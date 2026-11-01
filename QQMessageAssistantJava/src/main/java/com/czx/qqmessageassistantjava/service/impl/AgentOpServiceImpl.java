package com.czx.qqmessageassistantjava.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.common.FlexibleLocalDateTimeDeserializer;
import com.czx.qqmessageassistantjava.dto.AgentOpsRequest;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoChangeLog;
import com.czx.qqmessageassistantjava.vo.MemoReminderVO;
import com.czx.qqmessageassistantjava.mapper.MemoChangeLogMapper;
import com.czx.qqmessageassistantjava.service.AgentOpService;
import com.czx.qqmessageassistantjava.service.MemoService;
import com.czx.qqmessageassistantjava.vo.AgentOpsResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

@Service
public class AgentOpServiceImpl implements AgentOpService {

    private static final Logger log = LoggerFactory.getLogger(AgentOpServiceImpl.class);

    private static final Set<String> OPS = Set.of("memo.create", "memo.update", "memo.close");
    private static final Set<String> CLOSE_STATUSES = Set.of("DONE", "CANCELLED");
    private static final List<String> OPEN_STATUSES =
            List.of("ACTIVE", "REMINDED", "AWAITING_REPLY", "OVERDUE");
    private static final Set<String> CATEGORIES =
            Set.of("学业", "社交", "健康", "财务", "日常", "其他");

    private static final int MAX_TITLE = 255;
    private static final int MAX_CONTENT = 2000;
    private static final int SUMMARY_CONTENT = 40;
    private static final DateTimeFormatter SUMMARY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Autowired
    private MemoService memoService;

    @Autowired
    private MemoChangeLogMapper changeLogMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentOpsResult execute(AgentOpsRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        String eventId = trimToNull(request.getEventId());
        if (eventId == null) {
            throw new BusinessException("eventId 不能为空");
        }
        if (eventId.length() > 64) {
            throw new BusinessException("eventId 不能超过 64 字");
        }
        String op = trimToNull(request.getOp());
        if (op == null || !OPS.contains(op)) {
            throw new BusinessException("op 只能为 " + OPS + " 之一");
        }
        Long senderId = requireLong(request.getSenderId(), "senderId");

        // 先占位再去重：靠 uk_event 唯一索引把并发/重试挡在业务写入之前
        MemoChangeLog ledger = new MemoChangeLog();
        ledger.setMemoId(0L);
        ledger.setSenderId(senderId);
        ledger.setSource("AGENT");
        ledger.setOp(opTag(op));
        ledger.setEventId(eventId);
        ledger.setConversationId(trimToNull(request.getConversationId()));
        if (changeLogMapper.insertIgnore(ledger) == 0) {
            return replay(eventId);
        }

        OpOutcome outcome = switch (op) {
            case "memo.create" -> new OpOutcome(create(request, senderId), null);
            case "memo.update" -> update(request, senderId);
            case "memo.close" -> close(request, senderId);
            default -> throw new BusinessException("不支持的 op：" + op);
        };
        Memo memo = outcome.memo();

        AgentOpsResult result = buildResult(op, memo, false);
        ledger.setMemoId(memo.getId());
        ledger.setBeforeJson(outcome.beforeJson());
        ledger.setAfterJson(toJson(memo));
        ledger.setResultJson(toJson(result));
        changeLogMapper.updateById(ledger);
        return result;
    }

    @Override
    public List<MemoChangeLog> history(Long memoId) {
        return changeLogMapper.selectList(new LambdaQueryWrapper<MemoChangeLog>()
                .eq(MemoChangeLog::getMemoId, memoId)
                .orderByDesc(MemoChangeLog::getId));
    }

    private Memo create(AgentOpsRequest request, Long senderId) {
        String content = trimToNull(request.getContent());
        String title = trimToNull(request.getTitle());
        if (title == null) {
            title = content == null ? "备忘" : cut(content, SUMMARY_CONTENT);
        }
        if (title.length() > MAX_TITLE) {
            throw new BusinessException("title 不能超过 " + MAX_TITLE + " 字");
        }
        MemoCreateRequest create = new MemoCreateRequest();
        create.setSenderId(senderId);
        create.setChatId(optionalLong(request.getChatId(), "chatId"));
        create.setTitle(title);
        create.setContent(content == null ? null : cut(content, MAX_CONTENT));
        create.setImportance(clampImportance(optionalInt(request.getImportance(), "importance")));
        create.setCategory(normalizeCategory(request.getCategory()));
        create.setDueTime(parseDueTime(request.getDueTime()));
        return memoService.create(create);
    }

    private OpOutcome update(AgentOpsRequest request, Long senderId) {
        Long memoId = requireLong(request.getMemoId(), "memoId");
        if (trimToNull(request.getStatus()) != null) {
            throw new BusinessException("要完成或取消请用 memo.close");
        }
        Memo existing = requireOwnedMemo(memoId, senderId);
        String before = toJson(existing);

        MemoPatchRequest patch = new MemoPatchRequest();
        boolean touched = false;

        String title = trimToNull(request.getTitle());
        if (title != null) {
            if (title.length() > MAX_TITLE) {
                throw new BusinessException("title 不能超过 " + MAX_TITLE + " 字");
            }
            patch.setTitle(title);
            touched = true;
        }
        String content = trimToNull(request.getContent());
        if (content != null) {
            patch.setContent(cut(content, MAX_CONTENT));
            touched = true;
        }
        Integer importance = optionalInt(request.getImportance(), "importance");
        if (importance != null) {
            if (importance < 1 || importance > 5) {
                throw new BusinessException("importance 必须为 1-5");
            }
            patch.setImportance(importance);
            touched = true;
        }
        if (trimToNull(request.getCategory()) != null) {
            patch.setCategory(normalizeCategory(request.getCategory()));
            touched = true;
        }
        LocalDateTime dueTime = parseDueTime(request.getDueTime());
        if (dueTime != null) {
            patch.setDueTime(dueTime);
            touched = true;
        }
        if (Boolean.TRUE.equals(optionalBoolean(request.getClearDueTime()))) {
            patch.setClearDueTime(true);
            touched = true;
        }
        if (!touched) {
            throw new BusinessException("没有要修改的内容");
        }
        return new OpOutcome(memoService.patch(memoId, patch), before);
    }

    private OpOutcome close(AgentOpsRequest request, Long senderId) {
        String status = trimToNull(request.getStatus());
        status = status == null ? "DONE" : status.toUpperCase();
        if (!CLOSE_STATUSES.contains(status)) {
            throw new BusinessException("status 只能为 DONE 或 CANCELLED");
        }
        Long memoId = optionalLong(request.getMemoId(), "memoId");
        Memo target = memoId == null ? latestOpenMemo(senderId) : requireOwnedMemo(memoId, senderId);
        String before = toJson(target);

        MemoPatchRequest patch = new MemoPatchRequest();
        patch.setStatus(status);
        return new OpOutcome(memoService.patch(target.getId(), patch), before);
    }

    private Memo latestOpenMemo(Long senderId) {
        Memo memo = memoService.lambdaQuery()
                .eq(Memo::getSenderId, senderId)
                .in(Memo::getStatus, OPEN_STATUSES)
                .orderByDesc(Memo::getId)
                .last("LIMIT 1")
                .one();
        if (memo == null) {
            throw new BusinessException("现在没有待办的备忘");
        }
        return memo;
    }

    private Memo requireOwnedMemo(Long memoId, Long senderId) {
        Memo memo = memoService.getById(memoId);
        // 不区分「不存在」和「不是你的」，避免泄露别人的备忘 id
        if (memo == null || !senderId.equals(memo.getSenderId())) {
            throw new BusinessException("备忘录不存在");
        }
        return memo;
    }

    /** 一次写操作的结果 + 变更前快照（create 没有 before）。 */
    private record OpOutcome(Memo memo, String beforeJson) {
    }

    private AgentOpsResult replay(String eventId) {
        MemoChangeLog existing = changeLogMapper.selectByEventId(eventId);
        if (existing == null || existing.getResultJson() == null) {
            throw new BusinessException("这次操作正在处理，请稍后再试");
        }
        try {
            AgentOpsResult result = objectMapper.readValue(existing.getResultJson(), AgentOpsResult.class);
            result.setReplayed(true);
            return result;
        } catch (Exception e) {
            log.warn("memo_change_log.result_json 解析失败 eventId={}", eventId, e);
            throw new BusinessException("这次操作已经处理过，但结果读取失败");
        }
    }

    private AgentOpsResult buildResult(String op, Memo memo, boolean replayed) {
        AgentOpsResult result = new AgentOpsResult();
        result.setOp(op);
        result.setMemoId(memo.getId());
        result.setStatus(memo.getStatus());
        result.setReplayed(replayed);
        result.setMessage(messageFor(op, memo));
        result.setReminders(memoService.reminders(memo.getId()).stream()
                .filter(r -> "PENDING".equals(r.getStatus()))
                .map(this::brief)
                .toList());
        return result;
    }

    private AgentOpsResult.ReminderBrief brief(MemoReminderVO reminder) {
        AgentOpsResult.ReminderBrief brief = new AgentOpsResult.ReminderBrief();
        brief.setOffsetLabel(reminder.getOffsetLabel());
        brief.setRemindAt(reminder.getRemindAt());
        return brief;
    }

    private String messageFor(String op, Memo memo) {
        String summary = summary(memo);
        return switch (op) {
            case "memo.create" -> "记下了：" + summary;
            case "memo.update" -> "改好了：" + summary;
            case "memo.close" -> "CANCELLED".equals(memo.getStatus())
                    ? "已取消：" + summary
                    : "已完成：" + summary;
            default -> summary;
        };
    }

    private String summary(Memo memo) {
        StringBuilder sb = new StringBuilder(memo.getTitle() == null ? "备忘" : memo.getTitle());
        if (memo.getDueTime() != null) {
            sb.append(" · ").append(memo.getDueTime().format(SUMMARY_TIME));
        }
        if (memo.getContent() != null && !memo.getContent().isBlank()) {
            sb.append(" · ").append(cut(memo.getContent().replaceAll("\\s+", " "), SUMMARY_CONTENT));
        }
        return cut(sb.toString(), 120);
    }

    private String opTag(String op) {
        return switch (op) {
            case "memo.create" -> "CREATE";
            case "memo.update" -> "UPDATE";
            case "memo.close" -> "CLOSE";
            default -> op;
        };
    }

    private String normalizeCategory(String raw) {
        String category = trimToNull(raw);
        return category != null && CATEGORIES.contains(category) ? category : "其他";
    }

    private Integer clampImportance(Integer importance) {
        if (importance == null) {
            return 3;
        }
        return Math.max(1, Math.min(5, importance));
    }

    private LocalDateTime parseDueTime(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return FlexibleLocalDateTimeDeserializer.parseFlexible(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("dueTime " + e.getMessage());
        }
    }

    private static Long requireLong(String raw, String field) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BusinessException(field + " 不能为空");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(field + " 必须是数字");
        }
    }

    private static Long optionalLong(String raw, String field) {
        String value = trimToNull(raw);
        return value == null ? null : requireLong(value, field);
    }

    private static Integer optionalInt(String raw, String field) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(field + " 必须是数字");
        }
    }

    private static Boolean optionalBoolean(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化失败", e);
            return null;
        }
    }
}
