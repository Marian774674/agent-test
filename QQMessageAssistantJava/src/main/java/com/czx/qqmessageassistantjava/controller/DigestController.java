package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.FlexibleLocalDateTimeDeserializer;
import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.service.AppSettingService;
import com.czx.qqmessageassistantjava.service.ChatDigestService;
import com.czx.qqmessageassistantjava.service.impl.DigestRefreshService;
import com.czx.qqmessageassistantjava.vo.DigestItemVO;
import com.czx.qqmessageassistantjava.vo.DigestRefreshVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Tag(name = "消息整理")
@RestController
@RequestMapping("/api/digest")
public class DigestController {

    static final String LAST_SEEN_KEY = "last_digest_seen";

    private final ChatDigestService chatDigestService;
    private final AppSettingService appSettingService;
    private final DigestRefreshService digestRefreshService;

    @Autowired
    public DigestController(ChatDigestService chatDigestService,
                            AppSettingService appSettingService,
                            DigestRefreshService digestRefreshService) {
        this.chatDigestService = chatDigestService;
        this.appSettingService = appSettingService;
        this.digestRefreshService = digestRefreshService;
    }

    @Operation(summary = "进首页时调用：消息够多或隔得够久就整理一次「上次整理到现在」")
    @PostMapping("/refresh")
    public Result<DigestRefreshVO> refresh() {
        return Result.ok(digestRefreshService.refresh());
    }

    @Operation(summary = "上次离开之后的整理结果；since 缺省为 24 小时前")
    @GetMapping
    public Result<List<DigestItemVO>> list(@RequestParam(required = false) String since) {
        LocalDateTime from = FlexibleLocalDateTimeDeserializer.parseFlexible(since);
        if (from == null) {
            from = LocalDateTime.now().minusHours(24);
        }
        return Result.ok(chatDigestService.listSince(from));
    }

    @Operation(summary = "把当前时间记为「已看过」")
    @PostMapping("/seen")
    public Result<Integer> markSeen() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        return Result.ok(appSettingService.putAll(Map.of(LAST_SEEN_KEY, now.toString())));
    }

    @Operation(summary = "上次「已看过」的时间；没看过返回空串")
    @GetMapping("/last-seen")
    public Result<String> lastSeen() {
        return Result.ok(appSettingService.getOrDefault(LAST_SEEN_KEY, ""));
    }
}
