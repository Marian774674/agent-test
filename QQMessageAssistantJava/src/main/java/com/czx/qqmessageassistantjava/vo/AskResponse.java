package com.czx.qqmessageassistantjava.vo;

import lombok.Data;

import java.util.List;

@Data
public class AskResponse {
    private String answer;
    /** true=模型引用了候选集之外的东西，结论作废，只剩证据 */
    private Boolean degraded;
    private List<EvidenceVO> evidence;
    private ClarifyStateVO needClarify;
    /** 本次检索的范围与计数（spec §7.8）；老前端忽略即可 */
    private AskScopeVO scope;
}
