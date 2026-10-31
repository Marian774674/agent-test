package com.czx.qqmessageassistantjava.vo;

import lombok.Data;

/** 一条能点回原文的证据；id 带类型前缀，四张表自增不会撞车 */
@Data
public class EvidenceVO {
    private String type;
    private String id;
    private String label;
    private String content;
    /** 出处：群聊「群名 · 人名 · MM-dd HH:mm」，私聊「QQ 对方QQ · 人名 · MM-dd HH:mm」 */
    private String source;
    /** 第一条来源消息的原文，截 200 字 */
    private String quote;
}
