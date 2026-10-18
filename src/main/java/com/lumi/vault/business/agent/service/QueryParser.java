package com.lumi.vault.business.agent.service;

import lombok.extern.slf4j.Slf4j;


import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则式查询理解：多意图分词、同义词扩展、颜色识别、否定排除、结构化过滤（方向/年份/分辨率）。
 * 不调用任何模型；LLM 可用时会由 {@code LlmQueryAnalyzer} 在其结果上做增强。
 */
@Component
@Slf4j
public class QueryParser {
    private static final String SYNONYMS_RESOURCE = "/agent/synonyms.txt";
    private static final String[] NEGATION_PREFIXES = {"不要", "没有", "不含", "排除", "除了", "无", "not ", "no ", "-"};
    private static final String[] SEPARATORS = {"\\s+", "、", "，", ",", "。", "；", ";", "\\|", "/"};
    private static final Pattern YEAR_PATTERN = Pattern.compile("(\\d{4})\\s*年");

    private static final Map<String, String> COLOR_WORDS = buildColorWords();

    private final Map<String, List<String>> synonyms = loadSynonyms();

    public QueryIntent parse(String rawQuery, Collection<String> vocabulary) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            return new QueryIntent(query, List.of(), List.of(), List.of(), List.of(), QueryIntent.SearchFilters.EMPTY, null, null, Map.of());
        }
        String lower = query.toLowerCase(Locale.ROOT);

        List<String> positives = new ArrayList<>();
        List<String> negatives = new ArrayList<>();
        for (String fragment : splitFragments(lower)) {
            String trimmed = fragment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String negative = stripNegation(trimmed);
            if (negative != null) {
                if (!negative.isBlank()) {
                    negatives.add(negative);
                }
            } else {
                positives.add(trimmed);
            }
        }
        String positiveText = String.join(" ", positives);
        String negativeText = String.join(" ", negatives);

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        LinkedHashSet<String> colors = new LinkedHashSet<>();
        LinkedHashSet<String> excluded = new LinkedHashSet<>();

        collectTerms(positiveText, vocabulary, tokens, expanded, colors);
        if (!negativeText.isBlank()) {
            LinkedHashSet<String> ignoredTokens = new LinkedHashSet<>();
            LinkedHashSet<String> negativeColors = new LinkedHashSet<>();
            collectTerms(negativeText, vocabulary, ignoredTokens, excluded, negativeColors);
            excluded.addAll(ignoredTokens);
            excluded.addAll(negativeColors);
        }
        return new QueryIntent(query, List.copyOf(tokens), List.copyOf(expanded), List.copyOf(excluded),
                List.copyOf(colors), extractFilters(lower), null, null, Map.of());
    }

    private QueryIntent.SearchFilters extractFilters(String text) {
        String orientation = null;
        if (text.contains("横屏") || text.contains("横图") || text.contains("横向") || text.contains("landscape")) {
            orientation = "landscape";
        } else if (text.contains("竖屏") || text.contains("竖图") || text.contains("纵向") || text.contains("portrait")) {
            orientation = "portrait";
        } else if (text.contains("方图") || text.contains("square")) {
            orientation = "square";
        }

        String dateFrom = null;
        String dateTo = null;
        Matcher yearMatcher = YEAR_PATTERN.matcher(text);
        if (yearMatcher.find()) {
            int year = Integer.parseInt(yearMatcher.group(1));
            if (year >= 1900 && year <= 2100) {
                dateFrom = year + "-01-01";
                dateTo = year + "-12-31";
            }
        } else {
            Integer relativeYear = null;
            if (text.contains("今年")) {
                relativeYear = LocalDate.now().getYear();
            } else if (text.contains("去年")) {
                relativeYear = LocalDate.now().getYear() - 1;
            } else if (text.contains("前年")) {
                relativeYear = LocalDate.now().getYear() - 2;
            }
            if (relativeYear != null) {
                dateFrom = relativeYear + "-01-01";
                dateTo = relativeYear + "-12-31";
            }
        }

        Integer minLongSide = null;
        if (text.contains("4k") || text.contains("超清")) {
            minLongSide = 3840;
        } else if (text.contains("2k")) {
            minLongSide = 2560;
        } else if (text.contains("1080p")) {
            minLongSide = 1920;
        } else if (text.contains("高清") || text.contains("高分辨率")) {
            minLongSide = 1920;
        }
        return new QueryIntent.SearchFilters(orientation, dateFrom, dateTo, null, null, minLongSide);
    }

    private void collectTerms(String text, Collection<String> vocabulary, LinkedHashSet<String> tokens,
                              LinkedHashSet<String> expanded, LinkedHashSet<String> colors) {
        if (text.isBlank()) {
            return;
        }
        for (String word : text.split("[^a-z0-9\\u4e00-\\u9fff_\\-]+")) {
            if (word.length() >= 2 && (word.chars().allMatch(Character::isDigit) || !containsCjk(word))) {
                tokens.add(word);
            }
        }
        if (vocabulary != null) {
            for (String term : vocabulary) {
                if (term == null || term.isBlank()) {
                    continue;
                }
                String normalized = term.toLowerCase(Locale.ROOT);
                if (normalized.length() >= 1 && text.contains(normalized)) {
                    tokens.add(normalized);
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : synonyms.entrySet()) {
            if (text.contains(entry.getKey())) {
                expanded.addAll(entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : COLOR_WORDS.entrySet()) {
            if (text.contains(entry.getKey())) {
                colors.add(entry.getValue());
            }
        }
    }

    private String stripNegation(String fragment) {
        for (String prefix : NEGATION_PREFIXES) {
            if (fragment.startsWith(prefix) && fragment.length() > prefix.length()) {
                return fragment.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private List<String> splitFragments(String text) {
        String result = text;
        for (String separator : SEPARATORS) {
            result = result.replaceAll(separator, " ");
        }
        return List.of(result.split(" "));
    }

    private boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    private static Map<String, String> buildColorWords() {
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("红色", "red");
        colors.put("红", "red");
        colors.put("橙色", "orange");
        colors.put("橘色", "orange");
        colors.put("橙", "orange");
        colors.put("黄色", "yellow");
        colors.put("金黄", "yellow");
        colors.put("黄", "yellow");
        colors.put("绿色", "green");
        colors.put("绿", "green");
        colors.put("青色", "cyan");
        colors.put("天蓝", "cyan");
        colors.put("蓝色", "blue");
        colors.put("蓝", "blue");
        colors.put("紫色", "purple");
        colors.put("紫", "purple");
        colors.put("粉色", "pink");
        colors.put("粉红", "pink");
        colors.put("粉", "pink");
        colors.put("白色", "white");
        colors.put("白", "white");
        colors.put("黑色", "black");
        colors.put("黑", "black");
        colors.put("灰色", "gray");
        colors.put("灰", "gray");
        colors.put("棕色", "brown");
        colors.put("褐色", "brown");
        colors.put("咖啡色", "brown");
        colors.put("棕", "brown");
        return Map.copyOf(colors);
    }

    private Map<String, List<String>> loadSynonyms() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        try (InputStream stream = QueryParser.class.getResourceAsStream(SYNONYMS_RESOURCE)) {
            if (stream == null) {
                return Map.of();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
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
                List<String> values = new ArrayList<>();
                for (String value : trimmed.substring(index + 1).split(",")) {
                    String item = value.trim().toLowerCase(Locale.ROOT);
                    if (!item.isEmpty()) {
                        values.add(item);
                    }
                }
                if (!key.isEmpty() && !values.isEmpty()) {
                    map.put(key, List.copyOf(values));
                }
            }
        } catch (Exception e) {
            log.warn("加载同义词词典失败: {}", e.getMessage());
            return Map.of();
        }
        return Map.copyOf(map);
    }
}
