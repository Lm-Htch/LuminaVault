package com.lumi.vault.business.perception;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 感性词词典：查询侧"感觉词"→ 规范特征值，数据在 {@code /perception/query-lexicon.txt}，
 * 代码只负责加载与匹配（与 QueryParser 的同义词表同一套维护方式）。
 *
 * <p>文件格式（UTF-8，{@code #} 开头为注释）：{@code 查询词=facet:值1,值2;facet:值}
 * facet 取值：mood / style / tone / scene / usability，见 {@link #FACET_NAMES}。
 */
@Component
public class PerceptionLexicon {

    public static final String MOOD = "mood";
    public static final String STYLE = "style";
    public static final String TONE = "tone";
    public static final String SCENE = "scene";
    public static final String USABILITY = "usability";

    public static final Set<String> FACET_NAMES = Set.of(MOOD, STYLE, TONE, SCENE, USABILITY);

    private static final String QUERY_LEXICON_RESOURCE = "/perception/query-lexicon.txt";

    private final Map<String, Map<String, List<String>>> words;

    public PerceptionLexicon() {
        this.words = load(getClass().getResourceAsStream(QUERY_LEXICON_RESOURCE));
    }

    /** 从查询文本提取 facet → 规范值 列表（子串匹配，按出现去重）。 */
    public Map<String, List<String>> extract(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        Map<String, LinkedHashSet<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> entry : words.entrySet()) {
            if (!lower.contains(entry.getKey())) {
                continue;
            }
            for (Map.Entry<String, List<String>> facet : entry.getValue().entrySet()) {
                result.computeIfAbsent(facet.getKey(), key -> new LinkedHashSet<>()).addAll(facet.getValue());
            }
        }
        Map<String, List<String>> mapped = new LinkedHashMap<>();
        result.forEach((facet, values) -> mapped.put(facet, List.copyOf(values)));
        return mapped;
    }

    /** 合并两组 facet（去重，保留顺序）。 */
    public static Map<String, List<String>> merge(Map<String, List<String>> left, Map<String, List<String>> right) {
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        if (left != null) {
            left.forEach((facet, values) -> merged.computeIfAbsent(facet, key -> new LinkedHashSet<>()).addAll(values));
        }
        if (right != null) {
            right.forEach((facet, values) -> merged.computeIfAbsent(facet, key -> new LinkedHashSet<>()).addAll(values));
        }
        Map<String, List<String>> mapped = new LinkedHashMap<>();
        merged.forEach((facet, values) -> {
            List<String> list = new ArrayList<>(values);
            if (!list.isEmpty()) {
                mapped.put(facet, List.copyOf(list));
            }
        });
        return mapped;
    }

    /** 加载词典文件：{@code key=facet:值1,值2;facet:值}。文件缺失或格式错误时跳过该行。 */
    public static Map<String, Map<String, List<String>>> load(InputStream stream) {
        Map<String, Map<String, List<String>>> map = new LinkedHashMap<>();
        if (stream == null) {
            return map;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int index = trimmed.indexOf('=');
                if (index <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, index).trim().toLowerCase(Locale.ROOT);
                Map<String, List<String>> facets = parseFacetSpec(trimmed.substring(index + 1));
                if (!key.isEmpty() && !facets.isEmpty()) {
                    map.put(key, facets);
                }
            }
        } catch (Exception e) {
            // 资源损坏时视为空词典，由 LLM facets 路径兜底
        }
        return map;
    }

    /** 解析 {@code facet:值1,值2;facet:值} 片段。 */
    public static Map<String, List<String>> parseFacetSpec(String spec) {
        Map<String, List<String>> facets = new LinkedHashMap<>();
        for (String part : spec.split(";")) {
            int index = part.indexOf(':');
            if (index <= 0) {
                continue;
            }
            String facet = part.substring(0, index).trim().toLowerCase(Locale.ROOT);
            if (!FACET_NAMES.contains(facet)) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (String value : part.substring(index + 1).split(",")) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
            if (!values.isEmpty()) {
                facets.put(facet, values);
            }
        }
        return facets;
    }
}
