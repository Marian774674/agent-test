package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.dto.MemoPatchRequest;
import com.czx.qqmessageassistantjava.common.BusinessException;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoChangeLog;
import com.czx.qqmessageassistantjava.service.AgentOpService;
import com.czx.qqmessageassistantjava.service.MemoService;
import com.czx.qqmessageassistantjava.vo.MemoOpenItem;
import com.czx.qqmessageassistantjava.vo.MemoReminderVO;
import com.czx.qqmessageassistantjava.vo.ReminderPlanItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "备忘录",description = "创建/列表/编辑/完成/取消")
@RestController
@RequestMapping("/api/memos")
public class MemoController {

    @Autowired
    private MemoService memoService;

    @Autowired
    private AgentOpService agentOpService;


    @Operation(summary = "创建备忘", description = "按 category 展开提醒计划（importance=1 只存档不提醒）")
    @PostMapping
    public Result<Memo> create(@RequestBody MemoCreateRequest request){
        return Result.ok(memoService.create(request));
    }
    @Operation(summary = "获取备忘列表")
    @GetMapping
    public Result<List<Memo>> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) Long senderId) {
        return Result.ok(memoService.list(status, senderId));
    }
    @Operation(summary = "未完成备忘摘要", description = "给 Dify 指认「改哪一条」用；按到期时间升序")
    @GetMapping("/open")
    public Result<List<MemoOpenItem>> open(@RequestParam Long senderId,
                                           @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(memoService.openMemos(senderId, limit));
    }

    @Operation(summary = "变更记录", description = "AI 与人工的每次修改留痕，新的在前")
    @GetMapping("/{id}/history")
    public Result<List<MemoChangeLog>> history(@PathVariable Long id) {
        return Result.ok(agentOpService.history(id));
    }

    @Operation(summary = "备忘详情")
    @GetMapping("/{id}")
    public Result<Memo> detail(@PathVariable Long id) {
        Memo memo = memoService.getById(id);
        if (memo == null) {
            throw new BusinessException("备忘录不存在");
        }
        return Result.ok(memo);
    }

    @Operation(summary = "提醒计划", description = "每档提醒的时间、中文说明与发送状态")
    @GetMapping("/{id}/reminders")
    public Result<List<MemoReminderVO>> reminders(@PathVariable Long id) {
        return Result.ok(memoService.reminders(id));
    }

    @Operation(summary = "提醒预览", description = "按当前策略试算还没创建的备忘会怎么提醒，供编辑器实时展示")
    @GetMapping("/reminder-preview")
    public Result<List<ReminderPlanItemVO>> preview(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String dueTime,
            @RequestParam(required = false) Integer importance) {
        return Result.ok(memoService.previewPlan(category, dueTime, importance));
    }

    @Operation(summary = "重新排提醒", description = "按当前策略重排：旧的未发提醒置 CANCELLED，不删除")
    @PostMapping("/{id}/replan")
    public Result<List<MemoReminderVO>> replan(@PathVariable Long id) {
        memoService.replan(id);
        return Result.ok(memoService.reminders(id));
    }

    @Operation(summary = "编辑/完成/取消", description = "改 category/importance/dueTime 会重算提醒；DONE/CANCELLED 会撤销未发提醒")
    @PatchMapping("/{id}")
    public Result<Memo> patch(@PathVariable Long id,@RequestBody MemoPatchRequest request){
        return Result.ok(memoService.patch(id,request));
    }

}
