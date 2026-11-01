package com.czx.qqmessageassistantjava.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.entity.Draft;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.vo.MemoOpenItem;
import com.czx.qqmessageassistantjava.vo.MemoReminderVO;
import com.czx.qqmessageassistantjava.vo.ReminderPlanItemVO;

import java.util.List;

public interface MemoService extends IService<Memo> {

    Memo create(MemoCreateRequest request);

    List<Memo> list(String status,Long senderId);

    Memo patch(Long id, MemoPatchRequest request);

    Memo createFromDraft(Draft draft);

    /** 某条备忘的提醒计划（按提醒时间升序），供前端展示"什么时候会提醒我"。 */
    List<MemoReminderVO> reminders(Long memoId);

    /**
     * 按当前策略试算一条还没建的备忘会怎么提醒，供编辑器实时预览。
     *
     * @param category   事情类型，null/未知值按「其他」
     * @param dueTime    宽松格式的到期时间字符串，null/空 = 没有时间（不排提醒）
     * @param importance 1 = 只存档，不提醒
     */
    List<ReminderPlanItemVO> previewPlan(String category, String dueTime, Integer importance);

    /** 按当前策略重排某条备忘的未发提醒（旧档置 CANCELLED，不删）。 */
    void replan(Long memoId);

    /**
     * 未终结的备忘摘要（ACTIVE/REMINDED/AWAITING_REPLY/OVERDUE），
     * 按 dueTime 升序（没有时间的排后面）。喂给 Dify 让它能指认"改哪一条"。
     */
    List<MemoOpenItem> openMemos(Long senderId, int limit);

}
