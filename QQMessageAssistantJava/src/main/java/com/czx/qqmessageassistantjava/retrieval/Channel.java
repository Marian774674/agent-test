package com.czx.qqmessageassistantjava.retrieval;

/**
 * 检索通道。每个通道对应 Task 7 中的一路召回，{@link #key()} 是稳定的小写标识。
 */
public enum Channel {
    NOTE("note"),
    MSG_PERSON("msg-person"),
    MSG_MENTION("msg-mention"),
    MSG_LEXICAL("msg-lexical"),
    MSG_SEMANTIC("msg-semantic"),
    MEMO("memo"),
    FILE("file");

    private final String key;

    Channel(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
