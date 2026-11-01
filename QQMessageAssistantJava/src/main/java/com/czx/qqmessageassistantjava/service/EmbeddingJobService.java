package com.czx.qqmessageassistantjava.service;

/**
 * 消息向量索引任务：增量对齐、全量重建、启动加载与状态查询。
 *
 * <p>写入 {@code message_embedding} 与更新进程内 {@link com.czx.qqmessageassistantjava.embedding.EmbeddingIndex}
 * 是同一件事的两个面，三个会改动索引的方法都在服务实例上 {@code synchronized}，
 * 避免定时任务与将来的「提问前预跑」把同一条消息追加两次。
 */
public interface EmbeddingJobService {

    /**
     * 为最多 {@code limit} 条还没有向量的消息补向量。
     *
     * @return 本次新写入（并追加进内存索引）的行数；向量服务不可用时恒为 0
     */
    int indexMissing(int limit);

    /**
     * 删除该模型的全部向量后重新索引整张表，最后重新加载内存索引。
     *
     * @return 本次重新写入的行数；向量服务不可用时恒为 0（不删除已有数据）
     */
    int rebuild();

    /** 当前索引状态。 */
    EmbeddingStatus status();

    /**
     * 用 {@code message_embedding} 的全量数据重建内存索引（流式读取，不把全表读进 List）。
     *
     * <p>启动时自动调用。模型不可用或加载失败时只记日志、不抛异常，
     * 让应用照常启动，语义通道降级关闭。
     */
    void loadIndex();

    /**
     * @param semantic 语义检索通道是否可用（向量服务就绪且索引已成功加载）
     * @param indexed  当前内存索引中的向量条数
     * @param lag      尚缺向量的消息条数
     */
    record EmbeddingStatus(boolean semantic, int indexed, long lag) {
    }
}
