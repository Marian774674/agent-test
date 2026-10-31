package com.czx.qqmessageassistantjava.retrieval;

import java.time.LocalDateTime;

/**
 * 一路召回产生的候选条目。{@code type} 是业务类型（msg/note/memo/file 等），{@code id} 是该类型下的主键。
 *
 * @param type    业务类型
 * @param id      业务主键
 * @param label   展示用标题
 * @param content 展示用内容
 * @param at      时间戳；消息类为发送时间，笔记/备忘类可能为 null
 * @param source  来源标识
 * @param quote   引用片段
 */
public record Candidate(
        String type,
        long id,
        String label,
        String content,
        LocalDateTime at,
        String source,
        String quote) {

    /** 跨通道去重键，形如 {@code msg:42}。 */
    public String key() {
        return type + ":" + id;
    }
}
