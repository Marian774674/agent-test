package com.czx.qqmessageassistantjava.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息向量：只索引 {@code direction='IN'} 且内容非空的消息。
 *
 * <p>主键是 {@code (message_log_id, model)}——换模型等于全量重建，两套向量可以并存。
 * {@code vector} 是 float32 小端原始字节，长度 = {@code dim * 4}。
 */
@Data
@TableName("message_embedding")
public class MessageEmbedding {

    private Long messageLogId;

    /** 模型标识，换模型=全量重建 */
    private String model;

    private Integer dim;

    /** float32 小端，长度 = dim*4 */
    private byte[] vector;

    private LocalDateTime createdAt;
}
