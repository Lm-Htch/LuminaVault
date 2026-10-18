package com.lumi.vault.agent;

import com.lumi.vault.business.agent.service.QueryIntent;
import com.lumi.vault.business.agent.service.QueryParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QueryParserTest {

    private final QueryParser parser = new QueryParser();

    @Test
    void expandsSynonymsAndDetectsColors() {
        QueryIntent parsed = parser.parse("白发少女", Set.of());
        assertThat(parsed.expanded()).contains("white hair", "girl", "1girl");
        assertThat(parsed.colors()).contains("white");
    }

    @Test
    void extractsNegationFragments() {
        QueryIntent parsed = parser.parse("sea -mountain 不要红色", Set.of());
        assertThat(parsed.tokens()).contains("sea");
        assertThat(parsed.negatives()).contains("mountain", "red");
        assertThat(parsed.colors()).isEmpty();
    }

    @Test
    void matchesVocabularyTermsInsideChineseQuery() {
        QueryIntent parsed = parser.parse("海和山", Set.of("海", "山", "流水"));
        assertThat(parsed.tokens()).contains("海", "山");
    }

    @Test
    void detectsColorWords() {
        QueryIntent parsed = parser.parse("紫色 的 图", Set.of());
        assertThat(parsed.colors()).containsExactly("purple");
    }

    @Test
    void extractsStructuredFilters() {
        QueryIntent parsed = parser.parse("2023年的横屏高清图", Set.of());
        QueryIntent.SearchFilters filters = parsed.filters();
        assertThat(filters.orientation()).isEqualTo("landscape");
        assertThat(filters.dateFrom()).isEqualTo("2023-01-01");
        assertThat(filters.dateTo()).isEqualTo("2023-12-31");
        assertThat(filters.minLongSide()).isEqualTo(1920);
        assertThat(filters.describe()).contains("横屏", "2023年", "≥1920px");
    }
}
