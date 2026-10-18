package com.lumi.vault.business.perception;

import java.util.List;

/**
 * 感知画像（特征聚合结果）：把描述、标签、色板、尺寸等低层信号聚合为可检索的感性特征。
 * 所有值使用规范中文词（与 {@link PerceptionLexicon} 的查询词典同一词表）。
 */
public record PerceptionProfile(
        List<String> mood,
        List<String> style,
        List<String> tone,
        List<String> scene,
        List<String> usability,
        double wallpaperScore) {

    public static final PerceptionProfile EMPTY = new PerceptionProfile(
            List.of(), List.of(), List.of(), List.of(), List.of(), 0);

    /** 供重排文档拼接的文本（模糊/排序环节也能命中感性词）。 */
    public String describeText() {
        StringBuilder builder = new StringBuilder();
        appendAll(builder, mood);
        appendAll(builder, style);
        appendAll(builder, tone);
        appendAll(builder, scene);
        appendAll(builder, usability);
        return builder.toString().trim();
    }

    public boolean isEmpty() {
        return mood.isEmpty() && style.isEmpty() && tone.isEmpty() && scene.isEmpty() && usability.isEmpty();
    }

    private static void appendAll(StringBuilder builder, List<String> values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                builder.append(value).append(' ');
            }
        }
    }
}
