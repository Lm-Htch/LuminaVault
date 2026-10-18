package com.lumi.vault.business.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 检索意图：规则解析（QueryParser）与 LLM 拆解（LlmQueryAnalyzer）共用。
 * facets 为感性特征（mood/style/tone/scene/usability → 规范词），来自 LLM 与 {@code PerceptionLexicon} 的合并。
 */
public record QueryIntent(
        String raw,
        List<String> tokens,
        List<String> expanded,
        List<String> negatives,
        List<String> colors,
        SearchFilters filters,
        String characterHint,
        String rewrite,
        Map<String, List<String>> facets) {

    public record SearchFilters(String orientation, String dateFrom, String dateTo,
                                String author, String camera, Integer minLongSide) {

        public static final SearchFilters EMPTY = new SearchFilters(null, null, null, null, null, null);

        public boolean isEmpty() {
            return orientation == null && dateFrom == null && dateTo == null
                    && author == null && camera == null && minLongSide == null;
        }

        public List<String> describe() {
            List<String> labels = new ArrayList<>();
            if (orientation != null) {
                labels.add(switch (orientation) {
                    case "landscape" -> "横屏";
                    case "portrait" -> "竖屏";
                    default -> "方图";
                });
            }
            if (dateFrom != null || dateTo != null) {
                labels.add(describeDate());
            }
            if (author != null) {
                labels.add("作者:" + author);
            }
            if (camera != null) {
                labels.add("相机:" + camera);
            }
            if (minLongSide != null) {
                labels.add("≥" + minLongSide + "px");
            }
            return List.copyOf(labels);
        }

        private String describeDate() {
            if (dateFrom != null && dateTo != null
                    && dateFrom.endsWith("-01-01") && dateTo.endsWith("-12-31")
                    && dateFrom.substring(0, 4).equals(dateTo.substring(0, 4))) {
                return dateFrom.substring(0, 4) + "年";
            }
            return (dateFrom == null ? "" : dateFrom) + " ~ " + (dateTo == null ? "" : dateTo);
        }
    }

    public List<String> positiveTerms() {
        LinkedHashSet<String> terms = new LinkedHashSet<>(tokens);
        terms.addAll(expanded);
        return List.copyOf(terms);
    }
}
