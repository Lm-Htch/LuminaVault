package com.lumi.vault.business.perception;

import com.lumi.vault.business.perception.config.PerceptionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 特征聚合器：把低层信号（WD14 标签、主色板、尺寸）聚合为感知画像，并与 LLM 分析产出的感性特征合并。
 * 标签映射数据在 {@code /perception/tag-facets.txt}；壁纸评分权重来自 {@link PerceptionProperties}（配置可调）。
 */
@Component
@Slf4j
public class PerceptionAggregator {

    private static final String TAG_FACETS_RESOURCE = "/perception/tag-facets.txt";

    private static final List<String> WARM_COLORS = List.of("red", "orange", "yellow", "brown", "pink");
    private static final List<String> COOL_COLORS = List.of("blue", "cyan", "green", "purple");

    private final Map<String, Map<String, List<String>>> tagFacets;
    private final PerceptionProperties properties;
    private final ObjectMapper objectMapper;

    public PerceptionAggregator(PerceptionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tagFacets = PerceptionLexicon.load(getClass().getResourceAsStream(TAG_FACETS_RESOURCE));
    }

    /** 规则聚合：标签 + 色板 + 尺寸 → 画像（不含 LLM 特征）。 */
    public PerceptionProfile aggregate(List<String> tags, List<String> colors, Integer width, Integer height) {
        LinkedHashSet<String> mood = new LinkedHashSet<>();
        LinkedHashSet<String> style = new LinkedHashSet<>();
        LinkedHashSet<String> tone = new LinkedHashSet<>();
        LinkedHashSet<String> scene = new LinkedHashSet<>();
        LinkedHashSet<String> usability = new LinkedHashSet<>();

        List<String> normalizedTags = new ArrayList<>();
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                String value = tag.toLowerCase(Locale.ROOT).trim();
                normalizedTags.add(value);
                Map<String, List<String>> facets = tagFacets.get(value);
                if (facets == null) {
                    continue;
                }
                for (Map.Entry<String, List<String>> facet : facets.entrySet()) {
                    applyFacet(facet.getKey(), facet.getValue(), mood, style, tone, scene, usability);
                }
            }
        }

        applyToneFromPalette(colors, tone);
        double wallpaperScore = wallpaperScore(normalizedTags, colors, width, height);
        if (wallpaperScore >= properties.wallpaperThreshold()) {
            usability.add("适合壁纸");
        }
        if (isPortraitFriendly(normalizedTags, width, height)) {
            usability.add("适合头像");
        }
        return new PerceptionProfile(List.copyOf(mood), List.copyOf(style), List.copyOf(tone),
                List.copyOf(scene), List.copyOf(usability), wallpaperScore);
    }

    /** 合并：LLM 特征在前、规则特征在后（同值去重）。 */
    public PerceptionProfile merge(PerceptionProfile llm, PerceptionProfile rule) {
        return new PerceptionProfile(
                union(llm == null ? null : llm.mood(), rule.mood()),
                union(llm == null ? null : llm.style(), rule.style()),
                union(llm == null ? null : llm.tone(), rule.tone()),
                union(llm == null ? null : llm.scene(), rule.scene()),
                union(llm == null ? null : llm.usability(), rule.usability()),
                Math.max(llm == null ? 0 : llm.wallpaperScore(), rule.wallpaperScore()));
    }

    public String serialize(PerceptionProfile profile) {
        if (profile == null || (profile.isEmpty() && profile.wallpaperScore() <= 0)) {
            return null;
        }
        return objectMapper.writeValueAsString(profile);
    }

    public PerceptionProfile parse(String json) {
        if (json == null || json.isBlank()) {
            return PerceptionProfile.EMPTY;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return new PerceptionProfile(
                    strings(node.path("mood")),
                    strings(node.path("style")),
                    strings(node.path("tone")),
                    strings(node.path("scene")),
                    strings(node.path("usability")),
                    node.path("wallpaperScore").asDouble(0));
        } catch (Exception e) {
            log.debug("感知画像解析失败: {}", e.getMessage());
            return PerceptionProfile.EMPTY;
        }
    }

    /** 画像按 facet 展开（供"特征路"匹配与展示）。 */
    public Map<String, List<String>> valuesByFacet(PerceptionProfile profile) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (profile == null) {
            return values;
        }
        putIfNotEmpty(values, PerceptionLexicon.MOOD, profile.mood());
        putIfNotEmpty(values, PerceptionLexicon.STYLE, profile.style());
        putIfNotEmpty(values, PerceptionLexicon.TONE, profile.tone());
        putIfNotEmpty(values, PerceptionLexicon.SCENE, profile.scene());
        putIfNotEmpty(values, PerceptionLexicon.USABILITY, profile.usability());
        return values;
    }

    private void applyFacet(String facet, List<String> values,
                            LinkedHashSet<String> mood, LinkedHashSet<String> style, LinkedHashSet<String> tone,
                            LinkedHashSet<String> scene, LinkedHashSet<String> usability) {
        switch (facet) {
            case PerceptionLexicon.MOOD -> mood.addAll(values);
            case PerceptionLexicon.STYLE -> style.addAll(values);
            case PerceptionLexicon.TONE -> tone.addAll(values);
            case PerceptionLexicon.SCENE -> scene.addAll(values);
            case PerceptionLexicon.USABILITY -> usability.addAll(values);
            default -> {
            }
        }
    }

    private void applyToneFromPalette(List<String> colors, LinkedHashSet<String> tone) {
        if (colors == null || colors.isEmpty()) {
            return;
        }
        int warm = 0;
        int cool = 0;
        for (int i = 0; i < Math.min(colors.size(), 5); i++) {
            String color = colors.get(i) == null ? "" : colors.get(i).toLowerCase(Locale.ROOT);
            if (WARM_COLORS.contains(color)) {
                warm++;
            } else if (COOL_COLORS.contains(color)) {
                cool++;
            }
        }
        if (warm > cool && warm >= 2) {
            tone.add("暖色");
        } else if (cool > warm && cool >= 2) {
            tone.add("冷色");
        }
        List<String> top = colors.subList(0, Math.min(colors.size(), 3));
        if (top.contains("white") || top.contains("pink")) {
            tone.add("明亮");
        }
        if (top.contains("black")) {
            tone.add("暗调");
        }
    }

    private double wallpaperScore(List<String> tags, List<String> colors, Integer width, Integer height) {
        double score = 0;
        int longSide = Math.max(width == null ? 0 : width, height == null ? 0 : height);
        if (longSide >= 1920) {
            score += properties.wallpaperHighResBonus();
        } else if (longSide >= 1280) {
            score += properties.wallpaperMidResBonus();
        }
        if (width != null && height != null && width > height) {
            score += properties.wallpaperLandscapeBonus();
        }
        if (tags.contains("scenery") || tags.contains("no_humans") || tags.contains("landscape")) {
            score += properties.wallpaperSceneryBonus();
        }
        if (colors != null && !colors.isEmpty()) {
            String first = colors.get(0) == null ? "" : colors.get(0).toLowerCase(Locale.ROOT);
            if (!"black".equals(first)) {
                score += properties.wallpaperBrightPaletteBonus();
            }
        }
        return Math.min(1.0, score);
    }

    private boolean isPortraitFriendly(List<String> tags, Integer width, Integer height) {
        boolean squareOrPortrait = width == null || height == null || width <= height;
        return squareOrPortrait && (tags.contains("solo") || tags.contains("portrait"));
    }

    private List<String> union(List<String> first, List<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (first != null) {
            values.addAll(first);
        }
        if (second != null) {
            values.addAll(second);
        }
        return List.copyOf(values);
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asString("").trim();
                if (!value.isEmpty() && !values.contains(value)) {
                    values.add(value);
                }
                if (values.size() >= 12) {
                    break;
                }
            }
        }
        return List.copyOf(values);
    }

    private void putIfNotEmpty(Map<String, List<String>> target, String facet, List<String> values) {
        if (values != null && !values.isEmpty()) {
            target.put(facet, values);
        }
    }
}
