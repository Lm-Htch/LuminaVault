package com.lumi.vault.business.agent.service;

import lombok.extern.slf4j.Slf4j;


import com.lumi.vault.business.agent.client.AiClient;
import com.lumi.vault.business.agent.config.AiProperties;
import com.lumi.vault.business.perception.PerceptionLexicon;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 查询拆解：把自然语言搜索描述解析为结构化意图（多概念/标签/过滤条件）。
 * 结果按查询词缓存（TTL 见 lumina.ai.query-analyze-ttl）；LLM 不可用或解析失败时回退规则解析。
 */
@Service
@Slf4j
public class LlmQueryAnalyzer {
    private static final Set<String> ALLOWED_COLORS = Set.of(
            "red", "orange", "yellow", "green", "cyan", "blue",
            "purple", "pink", "white", "black", "gray", "brown");
    private static final Set<String> ALLOWED_ORIENTATIONS = Set.of("landscape", "portrait", "square");
    private static final int MAX_TERMS = 8;
    private static final int MAX_CACHE = 1000;

    private static final String SYSTEM_PROMPT = """
            你是私人图库的检索意图解析器。将用户的搜索描述拆解为结构化检索意图，只输出 JSON，不要输出其他内容：
            {"concepts":["中文概念"],"positive_tags":["english_tag"],"negative_tags":["english_tag"],
             "colors":["red","blue"],"character":null,"date_from":"YYYY-MM-DD","date_to":"YYYY-MM-DD",
             "orientation":"landscape","author":null,"camera":null,"min_long_side":1920,
             "facets":{"mood":[],"style":[],"tone":[],"scene":[],"usability":[]},
             "rewrite":"一句话概括用户想要什么"}
            规则：
            - concepts 保留用户原话中的关键概念（可多个，中文即可）；positive_tags 用常见 danbooru/wd14 风格英文标签
            - colors 只能从 red/orange/yellow/green/cyan/blue/purple/pink/white/black/gray/brown 中选
            - character 只在用户明确指名角色时填写角色名，否则 null
            - date_from/date_to/orientation/author/camera/min_long_side 只在用户明确表达时才填，否则 null
            - facets 把描述中的主观感受词归类：mood（氛围，如温馨/治愈/宁静/梦幻）、style（风格，如水彩/厚涂/极简）、
              tone（色调，如暖色/冷色/明亮/暗调）、scene（场景，如星空/夜晚/室内）、usability（用途，如适合壁纸/适合头像）；
              没有主观感受时给空数组，不要编造
            - 不要编造与描述无关的任何条件；negatives 只放用户明确排除的内容""";

    private final QueryParser queryParser;
    private final AiClient aiClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(QueryIntent intent, Instant expiresAt) {
    }

    public LlmQueryAnalyzer(QueryParser queryParser, AiClient aiClient, AiProperties properties, ObjectMapper objectMapper) {
        this.queryParser = queryParser;
        this.aiClient = aiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public QueryIntent analyze(String query, Collection<String> vocabulary) {
        QueryIntent ruleIntent = queryParser.parse(query, vocabulary);
        if (!properties.analyzeQuery() || !aiClient.chatEnabled() || query == null || query.isBlank()) {
            return ruleIntent;
        }
        String key = query.trim().toLowerCase(Locale.ROOT);
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.intent();
        }
        try {
            String content = aiClient.complete(SYSTEM_PROMPT, "搜索描述：" + query.trim());
            QueryIntent intent = parse(query, content, ruleIntent);
            if (intent != null) {
                if (cache.size() > MAX_CACHE) {
                    cache.clear();
                }
                cache.put(key, new Cached(intent, Instant.now().plus(properties.queryAnalyzeTtl())));
                return intent;
            }
        } catch (Exception e) {
            log.warn("LLM 意图拆解失败，回退规则解析: {}", e.getMessage());
        }
        return ruleIntent;
    }

    private QueryIntent parse(String query, String content, QueryIntent ruleIntent) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripCodeFence(content));
        } catch (Exception e) {
            return null;
        }
        List<String> concepts = normalizedStrings(root.path("concepts"));
        List<String> positiveTags = normalizedStrings(root.path("positive_tags"));
        String rewrite = bounded(textOrNull(root, "rewrite"), 300);
        if (concepts.isEmpty() && positiveTags.isEmpty() && (rewrite == null || rewrite.isBlank())) {
            return null;
        }

        LinkedHashSet<String> negatives = new LinkedHashSet<>(ruleIntent.negatives());
        negatives.addAll(normalizedStrings(root.path("negative_tags")));

        LinkedHashSet<String> colors = new LinkedHashSet<>(ruleIntent.colors());
        for (String color : normalizedStrings(root.path("colors"))) {
            if (ALLOWED_COLORS.contains(color)) {
                colors.add(color);
            }
        }

        QueryIntent.SearchFilters rule = ruleIntent.filters();
        String orientation = ALLOWED_ORIENTATIONS.contains(root.path("orientation").asString(""))
                ? root.path("orientation").asString("") : rule.orientation();
        String dateFrom = firstValidDate(root.path("date_from").asString(""), rule.dateFrom());
        String dateTo = firstValidDate(root.path("date_to").asString(""), rule.dateTo());
        String author = bounded(textOrNull(root, "author"), 100);
        if (author == null) {
            author = rule.author();
        }
        String camera = bounded(textOrNull(root, "camera"), 100);
        if (camera == null) {
            camera = rule.camera();
        }
        Integer minLongSide = rule.minLongSide();
        JsonNode minNode = root.path("min_long_side");
        if (minNode.isNumber()) {
            int value = minNode.asInt();
            if (value >= 64 && value <= 20000) {
                minLongSide = value;
            }
        }
        QueryIntent.SearchFilters filters = new QueryIntent.SearchFilters(
                orientation, dateFrom, dateTo, author, camera, minLongSide);

        String character = bounded(textOrNull(root, "character"), 128);
        return new QueryIntent(query, concepts, positiveTags, List.copyOf(negatives), List.copyOf(colors),
                filters, character, rewrite, parseFacets(root.path("facets")));
    }

    /** 解析 LLM 输出的感性特征：仅保留词典认可的 facet 名，值去重限长；无有效值时返回空。 */
    private Map<String, List<String>> parseFacets(JsonNode node) {
        Map<String, List<String>> facets = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return facets;
        }
        for (String facet : PerceptionLexicon.FACET_NAMES) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node.path(facet)) {
                String value = item.asString("").trim();
                if (!value.isEmpty() && value.length() <= 32 && !values.contains(value) && values.size() < 8) {
                    values.add(value);
                }
            }
            if (!values.isEmpty()) {
                facets.put(facet, List.copyOf(values));
            }
        }
        return facets;
    }

    private List<String> normalizedStrings(JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asString("").trim().toLowerCase(Locale.ROOT);
                if (!value.isEmpty() && value.length() <= 128) {
                    values.add(value);
                }
                if (values.size() >= MAX_TERMS) {
                    break;
                }
            }
        }
        return new ArrayList<>(values);
    }

    private String textOrNull(JsonNode root, String field) {
        String value = root.path(field).asString("").trim();
        return value.isEmpty() ? null : value;
    }

    private String bounded(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String firstValidDate(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            try {
                return LocalDate.parse(value.trim()).toString();
            } catch (DateTimeParseException ignored) {
                // 非法日期，用规则结果兜底
            }
        }
        return fallback;
    }

    private String stripCodeFence(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.trim();
        if (cleaned.startsWith("```")) {
            int firstLine = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                cleaned = cleaned.substring(firstLine + 1, lastFence).trim();
            }
        }
        return cleaned;
    }
}
