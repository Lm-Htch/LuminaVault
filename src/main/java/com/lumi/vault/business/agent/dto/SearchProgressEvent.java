package com.lumi.vault.business.agent.dto;

import java.util.Map;

/**
 * 智能搜索的真实进度事件：由检索管线各阶段产生，通过 SSE 推送给前端。
 * detail 携带该阶段的结构化数据（各路召回数量、图扩展种子/邻居数、去重抑制数等），
 * 供「搜索过程」节点流程板块渲染。
 */
public record SearchProgressEvent(String stage, String message, Integer current, Integer total,
                                  Map<String, Object> detail) {

    public static SearchProgressEvent of(String stage, String message) {
        return new SearchProgressEvent(stage, message, null, null, null);
    }

    public static SearchProgressEvent of(String stage, String message, Map<String, Object> detail) {
        return new SearchProgressEvent(stage, message, null, null, detail);
    }
}
