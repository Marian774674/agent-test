package com.czx.qqmessageassistantjava.vo;

import lombok.Data;

import java.util.Map;

/**
 * 一次问答的检索范围（spec §7.8）。
 *
 * <p>{@code channels} 的键恒为七个召回通道；值为 {@code null} 表示该路没跑、不可用或失败，
 * {@code 0} 表示跑了但没有命中。两者不能混。
 */
@Data
public class AskScopeVO {

    /** 语义通道是否可用 */
    private Boolean semantic;

    /** 尚未建立向量的消息条数 */
    private Integer lag;

    /** 实际跑了几轮（本轮单轮编排恒为 0 或 1） */
    private Integer rounds;

    /** 去重、预算截断前的候选数 */
    private Integer candidateCnt;

    /** 通道 -> 去重前原始命中数；null 表示该路没跑 */
    private Map<String, Integer> channels;

    /** 给人看的一句话；空表示无需解释 */
    private String message;
}
