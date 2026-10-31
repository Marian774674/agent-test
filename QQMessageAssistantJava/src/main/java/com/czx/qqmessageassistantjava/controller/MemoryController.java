package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.common.Result;
import com.czx.qqmessageassistantjava.service.EmbeddingJobService;
import com.czx.qqmessageassistantjava.service.MemoryJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "记忆归纳")
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryJobService memoryJobService;
    private final EmbeddingJobService embeddingJobService;

    public MemoryController(MemoryJobService memoryJobService,
                            EmbeddingJobService embeddingJobService) {
        this.memoryJobService = memoryJobService;
        this.embeddingJobService = embeddingJobService;
    }

    @Operation(summary = "从头补算全部历史消息（首次启用时手工跑一次）")
    @PostMapping("/rebuild")
    public Result<Integer> rebuild() {
        return Result.ok(memoryJobService.rebuild());
    }

    @Operation(summary = "删除并重建语义向量索引，返回重新写入的行数")
    @PostMapping("/embeddings/rebuild")
    public Result<Integer> rebuildEmbeddings() {
        return Result.ok(embeddingJobService.rebuild());
    }

    @Operation(summary = "语义索引状态：通道是否可用、已索引条数、待索引条数")
    @GetMapping("/embeddings/status")
    public Result<EmbeddingJobService.EmbeddingStatus> embeddingStatus() {
        return Result.ok(embeddingJobService.status());
    }
}
