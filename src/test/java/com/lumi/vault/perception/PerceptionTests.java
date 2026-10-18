package com.lumi.vault.perception;

import com.lumi.vault.business.perception.PerceptionAggregator;
import com.lumi.vault.business.perception.PerceptionLexicon;
import com.lumi.vault.business.perception.PerceptionProfile;
import com.lumi.vault.business.perception.config.PerceptionProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PerceptionTests {

    private final PerceptionAggregator aggregator = new PerceptionAggregator(
            new PerceptionProperties(0.6, 0.35, 0.15, 0.15, 0.2, 0.1), new ObjectMapper());

    private final PerceptionLexicon lexicon = new PerceptionLexicon();

    @Test
    void aggregatesTagsPaletteAndSize() {
        PerceptionProfile profile = aggregator.aggregate(
                List.of("watercolor_(medium)", "scenery", "no_humans"),
                List.of("orange", "yellow", "white"),
                1920, 1080);

        assertThat(profile.style()).contains("水彩");
        assertThat(profile.scene()).contains("全景风景");
        assertThat(profile.tone()).contains("暖色", "明亮");
        assertThat(profile.wallpaperScore()).isGreaterThanOrEqualTo(0.6);
        assertThat(profile.usability()).contains("适合壁纸");
        assertThat(profile.describeText()).contains("水彩").contains("暖色");
    }

    @Test
    void mergesLlmFacetsBeforeRuleFacets() {
        PerceptionProfile llm = new PerceptionProfile(List.of("温馨"), List.of(), List.of(), List.of(), List.of(), 0);
        PerceptionProfile merged = aggregator.merge(llm, aggregator.aggregate(List.of("fantasy"), List.of(), 800, 600));

        assertThat(merged.mood()).containsExactly("温馨", "梦幻");
    }

    @Test
    void serializesAndParsesRoundTrip() {
        PerceptionProfile profile = aggregator.aggregate(List.of("starry_sky"), List.of("blue", "black"),
                3840, 2160);
        String json = aggregator.serialize(profile);
        assertThat(json).isNotBlank();

        PerceptionProfile parsed = aggregator.parse(json);
        assertThat(parsed.scene()).contains("星空");
        assertThat(parsed.mood()).contains("梦幻");
        assertThat(parsed.wallpaperScore()).isEqualTo(profile.wallpaperScore());
        assertThat(aggregator.parse("not json")).isEqualTo(PerceptionProfile.EMPTY);
    }

    @Test
    void extractsSubjectiveFacetsFromQuery() {
        Map<String, List<String>> facets = lexicon.extract("看着很温馨，适合做壁纸的图");

        assertThat(facets.get(PerceptionLexicon.MOOD)).contains("温馨");
        assertThat(facets.get(PerceptionLexicon.TONE)).contains("暖色");
        assertThat(facets.get(PerceptionLexicon.USABILITY)).contains("适合壁纸");
    }

    @Test
    void lexiconMergeDedupes() {
        Map<String, List<String>> merged = PerceptionLexicon.merge(
                Map.of(PerceptionLexicon.MOOD, List.of("宁静")),
                Map.of(PerceptionLexicon.MOOD, List.of("宁静", "治愈")));

        assertThat(merged.get(PerceptionLexicon.MOOD)).containsExactly("宁静", "治愈");
    }
}
