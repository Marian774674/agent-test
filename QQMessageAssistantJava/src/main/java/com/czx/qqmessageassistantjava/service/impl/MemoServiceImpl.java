package com.czx.qqmessageassistantjava.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.common.FlexibleLocalDateTimeDeserializer;
import com.czx.qqmessageassistantjava.controller.MemoController;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.entity.InterceptRule;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoReminder;
import com.czx.qqmessageassistantjava.mapper.MemoMapper;
import com.czx.qqmessageassistantjava.mapper.MemoReminderMapper;
import com.czx.qqmessageassistantjava.memo.ReminderPlanner;
import com.czx.qqmessageassistantjava.memo.ReminderPolicyProperties;
import com.czx.qqmessageassistantjava.service.MemoService;
import com.czx.qqmessageassistantjava.vo.MemoOpenItem;
import com.czx.qqmessageassistantjava.vo.MemoReminderVO;
import com.czx.qqmessageassistantjava.vo.ReminderPlanItemVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MemoServiceImpl extends ServiceImpl<MemoMapper, Memo> implements MemoService {

    private static final Set<String> CATEGORIES =
            Set.of("学业","社交","健康","财务","日常","其他");
    private static final Set<String> PATCH_STATUSES = Set.of("DONE","CANCELLED");

    @Autowired
    private MemoReminderMapper memoReminderMapper;

    @Autowired
    private ReminderPolicyProperties reminderPolicyProperties;

    @Override
    @Transactional
    public Memo create(MemoCreateRequest request) {
        if(request.getSenderId() == null){
            throw new BusinessException("SenderId 不能为空");
        }
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new BusinessException("title 不能为空");
        }
        if (request.getImportance() == null || request.getImportance() < 1 || request.getImportance() > 5) {
            throw new BusinessException("importance 必须为 1-5");
        }
        if (request.getCategory() != null && !request.getCategory().isBlank()
                && !CATEGORIES.contains(request.getCategory())) {
            throw new BusinessException("category 只能为 " + CATEGORIES + " 之一");
        }
        Memo memo = BeanUtil.copyProperties(request, Memo.class);
        memo.setStatus("ACTIVE");
        save(memo);
        expandReminders(memo);
        return memo;
    }

    @Override
    public List<Memo> list(String status, Long senderId) {
        return lambdaQuery()
                .eq(status != null, Memo::getStatus, status)
                .eq(senderId != null, Memo::getSenderId, senderId)
                .orderByDesc(Memo::getCreatedAt)
                .list();
    }

    @Override
    @Transactional
    public Memo patch(Long id, MemoPatchRequest request) {
        Memo memo = getById(id);
        if (memo == null){
            throw new BusinessException("备忘录不存在");
        }
        if(request.getStatus()!=null && !PATCH_STATUSES.contains(request.getStatus())){
            throw new BusinessException("status 只能为 " + PATCH_STATUSES + " 之一");
        }
        if (request.getImportance() != null
                && (request.getImportance() < 1 || request.getImportance() > 5)) {
            throw new BusinessException("importance 必须为 1-5");
        }
        if (request.getCategory() != null && !request.getCategory().isBlank()
                && !CATEGORIES.contains(request.getCategory())) {
            throw new BusinessException("category 只能为 " + CATEGORIES + " 之一");
        }
        boolean clearDueTime = Boolean.TRUE.equals(request.getClearDueTime());
        // 计划按 category 选，所以改分类同样要重排——旧实现在这里漏了它
        boolean needRecalc = (request.getImportance() != null && !memo.getImportance().equals(request.getImportance())
            || request.getDueTime() != null && !request.getDueTime().equals(memo.getDueTime())
            || request.getCategory() != null && !request.getCategory().isBlank()
                && !request.getCategory().equals(memo.getCategory()))
            || clearDueTime;
        BeanUtil.copyProperties(request,memo, CopyOptions.create().ignoreNullValue());
        if (clearDueTime) {
            memo.setDueTime(null);
        }
        updateById(memo);

        if("DONE".equals(memo.getStatus()) || "CANCELLED".equals(memo.getStatus())){
            cancelPendingReminders(id);
        } else if (needRecalc) {
            cancelPendingReminders(id);
            expandReminders(memo);
        }
        return memo;
    }

    @Override
    @Transactional
    public Memo createFromDraft(Draft draft) {
        Map<String, Object> payload = draft.getPayload();
        MemoCreateRequest request = new MemoCreateRequest();
        request.setSenderId(draft.getSenderId());
        request.setChatId(draft.getChatId());
        request.setTitle((String) payload.get("title"));
        request.setContent((String) payload.get("content"));
        request.setCategory((String) payload.get("category"));
        Object importance = payload.get("importance");
        if (importance instanceof Number num) {
            request.setImportance(num.intValue());
        }
        Object dueTime = payload.get("dueTime");
        if (dueTime instanceof String str && !str.isBlank()) {
            request.setDueTime(LocalDateTime.parse(str));
        }
        return create(request);
    }

    private void expandReminders(Memo memo) {
        List<ReminderPlanner.Planned> plan = reminderPolicyProperties.toPlanner()
                .plan(memo.getCategory(), memo.getDueTime(), memo.getImportance(), LocalDateTime.now());
        for (ReminderPlanner.Planned planned : plan) {
            MemoReminder reminder = new MemoReminder();
            reminder.setMemoId(memo.getId());
            reminder.setOffsetLabel(planned.label());
            reminder.setRemindAt(planned.remindAt());
            reminder.setStatus("PENDING");
            memoReminderMapper.insert(reminder);
        }
    }

    private static final String[] OPEN_STATUSES =
            {"ACTIVE", "REMINDED", "AWAITING_REPLY", "OVERDUE"};

    @Override
    public List<MemoOpenItem> openMemos(Long senderId, int limit) {
        if (senderId == null) {
            throw new BusinessException("senderId 不能为空");
        }
        int size = Math.max(1, Math.min(limit, 50));
        QueryWrapper<Memo> wrapper = new QueryWrapper<Memo>()
                .eq("sender_id", senderId)
                .in("status", (Object[]) OPEN_STATUSES)
                // MySQL 里 NULL 排最前，先按 IS NULL 分档，再按时间升序
                .orderByAsc("due_time IS NULL")
                .orderByAsc("due_time")
                .orderByDesc("updated_at")
                .last("LIMIT " + size);
        return list(wrapper).stream().map(this::toOpenItem).toList();
    }

    private MemoOpenItem toOpenItem(Memo memo) {
        MemoOpenItem item = new MemoOpenItem();
        item.setId(memo.getId());
        item.setTitle(memo.getTitle());
        item.setContent(cut(memo.getContent(), 60));
        item.setDueTime(memo.getDueTime());
        item.setStatus(memo.getStatus());
        item.setImportance(memo.getImportance());
        item.setCategory(memo.getCategory());
        return item;
    }

    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    @Override
    public List<MemoReminderVO> reminders(Long memoId) {
        return memoReminderMapper.selectList(
                        new LambdaQueryWrapper<MemoReminder>()
                                .eq(MemoReminder::getMemoId, memoId)
                                .orderByAsc(MemoReminder::getRemindAt))
                .stream()
                .map(this::toReminderVO)
                .toList();
    }

    @Override
    public List<ReminderPlanItemVO> previewPlan(String category, String dueTime, Integer importance) {
        LocalDateTime parsedDue = parseDueTime(dueTime);
        return reminderPolicyProperties.toPlanner()
                .plan(category, parsedDue, importance, LocalDateTime.now())
                .stream()
                .map(planned -> {
                    ReminderPlanItemVO item = new ReminderPlanItemVO();
                    item.setLabel(planned.label());
                    item.setText(ReminderPlanner.describe(planned.label()));
                    item.setRemindAt(planned.remindAt());
                    return item;
                })
                .toList();
    }

    @Override
    @Transactional
    public void replan(Long memoId) {
        Memo memo = getById(memoId);
        if (memo == null) {
            throw new BusinessException("备忘录不存在");
        }
        if ("DONE".equals(memo.getStatus()) || "CANCELLED".equals(memo.getStatus())) {
            throw new BusinessException("已结束的备忘不需要再排提醒");
        }
        cancelPendingReminders(memoId);
        expandReminders(memo);
    }

    private static LocalDateTime parseDueTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return FlexibleLocalDateTimeDeserializer.parseFlexible(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("dueTime " + e.getMessage());
        }
    }

    private MemoReminderVO toReminderVO(MemoReminder reminder) {
        MemoReminderVO vo = new MemoReminderVO();
        vo.setId(reminder.getId());
        vo.setOffsetLabel(reminder.getOffsetLabel());
        vo.setOffsetText(ReminderPlanner.describe(reminder.getOffsetLabel()));
        vo.setRemindAt(reminder.getRemindAt());
        vo.setStatus(reminder.getStatus());
        vo.setSentAt(reminder.getSentAt());
        return vo;
    }

    private void cancelPendingReminders(Long id) {
        LambdaUpdateWrapper<MemoReminder> wrapper = new LambdaUpdateWrapper<MemoReminder>()
                .eq(MemoReminder::getMemoId, id)
                .eq(MemoReminder::getStatus, "PENDING")
                .set(MemoReminder::getStatus, "CANCELLED");
        memoReminderMapper.update(null, wrapper);
    }
}
