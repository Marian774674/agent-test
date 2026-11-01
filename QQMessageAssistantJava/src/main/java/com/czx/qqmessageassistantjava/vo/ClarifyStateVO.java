package com.czx.qqmessageassistantjava.vo;

import lombok.Data;

import java.util.List;

@Data
public class ClarifyStateVO {
    private String alias;
    private List<ClarifyCandidateVO> candidates;
    /** 该称呼在 IN 消息正文里出现的行数（跨会话，显示上限 999）；未知 alias 才有值 */
    private Integer occurrences;
}
