package com.lumi.vault.websearch;

import com.lumi.vault.business.websearch.client.SauceNaoParser;
import com.lumi.vault.business.websearch.client.WebSourceException;
import com.lumi.vault.business.websearch.dto.WebSourceResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SauceNaoParserTest {

    private final SauceNaoParser parser = new SauceNaoParser(JsonMapper.builder().build());

    private static final String SAMPLE = """
            {"header":{"status":0,"results_returned":1},
             "results":[{"header":{"similarity":"92.5","index_id":5},
                         "data":{"pixiv_id":123456,"title":"作品标题","author_name":"画师A",
                                 "ext_urls":["https://www.pixiv.net/artworks/123456"],
                                 "characters":"hatsune_miku"}}]}
            """;

    @Test
    void parsesPixivSource() {
        WebSourceResult result = parser.parse(SAMPLE, "saucenao", 0.5);
        assertThat(result).isNotNull();
        assertThat(result.similarity()).isEqualTo(0.925);
        assertThat(result.sourceType()).isEqualTo("pixiv");
        assertThat(result.sourceId()).isEqualTo("123456");
        assertThat(result.sourceUrl()).contains("pixiv.net/artworks/123456");
        assertThat(result.author()).isEqualTo("画师A");
        assertThat(result.title()).isEqualTo("作品标题");
        assertThat(result.characters()).containsExactly("hatsune_miku");
    }

    @Test
    void belowThresholdReturnsNull() {
        assertThat(parser.parse(SAMPLE, "saucenao", 0.99)).isNull();
    }

    @Test
    void quotaStatusThrowsQuotaException() {
        assertThatThrownBy(() -> parser.parse("{\"header\":{\"status\":-2}}", "saucenao", 0.5))
                .isInstanceOf(WebSourceException.class)
                .satisfies(error -> assertThat(((WebSourceException) error).quota()).isTrue());
    }

    @Test
    void parsesExtUrlWithoutPixivId() {
        String json = """
                {"header":{"status":0},
                 "results":[{"header":{"similarity":"88"},
                             "data":{"ext_urls":["https://twitter.com/i/status/999"],
                                     "creator":["Pixiv Artist"]}}]}
                """;
        WebSourceResult result = parser.parse(json, "saucenao", 0.5);
        assertThat(result).isNotNull();
        assertThat(result.sourceType()).isEqualTo("twitter");
        assertThat(result.author()).isEqualTo("Pixiv Artist");
    }
}
