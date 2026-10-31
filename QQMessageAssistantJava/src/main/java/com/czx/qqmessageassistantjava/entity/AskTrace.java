package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次问答的检索轨迹：漏答时能回答「当时查了哪几路、各命中几条、向量索引落后多少」。
 *
 * <p>只存问题与计数，不存原文正文。
 */
@Data
@TableName("ask_trace")
public class AskTrace {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String question;

    private String entities;

    private String queries;

    /** 命中非空的召回通道，逗号分隔 */
    private String channels;

    private Integer candidateCnt;

    private Integer rounds;

    private String answer;

    /** TINYINT 语义上是布尔：模型给的结论不可信时为 true */
    private Boolean degraded;

    private Integer latencyMs;

    private LocalDateTime createdAt;
}
