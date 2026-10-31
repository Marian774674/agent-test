package com.czx.qqmessageassistantjava.mapper;

import com.czx.qqmessageassistantjava.entity.MessageEmbedding;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;

/**
 * 消息向量表：主键 {@code (message_log_id, model)}，没有自增 id，所以不继承 BaseMapper。
 */
@Mapper
public interface MessageEmbeddingMapper {

    /** 还缺向量的消息：{@code direction='IN'}、内容非空、该模型下没有行。 */
    record MissingMessage(long id, String content) {
    }

    @Insert("INSERT INTO message_embedding (message_log_id, model, dim, vector) "
            + "VALUES (#{messageLogId}, #{model}, #{dim}, #{vector}) "
            + "ON DUPLICATE KEY UPDATE dim = VALUES(dim), vector = VALUES(vector)")
    int upsert(MessageEmbedding embedding);

    @Select("SELECT message_log_id, model, dim, vector, created_at FROM message_embedding "
            + "WHERE message_log_id = #{messageLogId} AND model = #{model}")
    MessageEmbedding selectOne(@Param("messageLogId") long messageLogId, @Param("model") String model);

    /**
     * 用「缺行」驱动增量索引：重启、换模型、删行都不会错位。
     */
    @Select("SELECT m.id, m.content FROM message_log m "
            + "LEFT JOIN message_embedding e "
            + "       ON e.message_log_id = m.id AND e.model = #{model} "
            + "WHERE m.direction = 'IN' "
            + "  AND m.content <> '' "
            + "  AND e.message_log_id IS NULL "
            + "ORDER BY m.id "
            + "LIMIT #{limit}")
    List<MissingMessage> selectMissing(@Param("model") String model, @Param("limit") int limit);

    /**
     * 启动时把该模型的全量向量流式读进 off-heap 缓冲；50 万行不能一次性堆进内存。
     */
    @Select("SELECT message_log_id, model, dim, vector, created_at FROM message_embedding "
            + "WHERE model = #{model} ORDER BY message_log_id")
    @ResultType(MessageEmbedding.class)
    void selectAll(@Param("model") String model, ResultHandler<MessageEmbedding> handler);

    @Select("SELECT COUNT(*) FROM message_embedding WHERE model = #{model}")
    long countByModel(@Param("model") String model);

    @Delete("DELETE FROM message_embedding WHERE model = #{model}")
    int deleteByModel(@Param("model") String model);
}
