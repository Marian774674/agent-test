package com.czx.qqmessageassistantjava.retrieval;

import com.czx.qqmessageassistantjava.entity.MessageLog;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息出处的一处实现（spec §7.5）。
 *
 * <p>群聊：{@code 三班群 · 小明 · 10-28 21:03}；私聊：{@code QQ 123456 · 小红 · 10-28 21:03}；
 * 没有昵称时退回 QQ 号。召回候选的 {@code source} 与 note 证据的出处都从这里生成，
 * 避免两套格式各说各话。
 */
public final class SourceFormat {

    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private SourceFormat() {
    }

    public static String messageSource(MessageLog row) {
        if (row == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (row.getChatId() != null) {
            parts.add(isBlank(row.getChatName()) ? "群 " + row.getChatId() : row.getChatName().trim());
        } else if (row.getSenderId() != null) {
            parts.add("QQ " + row.getSenderId());
        }
        appendSender(parts, row);
        if (row.getCreatedAt() != null) {
            parts.add(MM_DD.format(row.getCreatedAt()));
        }
        return String.join(" · ", parts);
    }

    private static void appendSender(List<String> parts, MessageLog row) {
        if (row.getSenderId() != null) {
            parts.add(isBlank(row.getSenderName())
                    ? String.valueOf(row.getSenderId()) : row.getSenderName().trim());
        } else if (!isBlank(row.getSenderName())) {
            parts.add(row.getSenderName().trim());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
