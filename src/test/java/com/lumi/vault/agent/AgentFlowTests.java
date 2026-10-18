package com.lumi.vault.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lumi.vault.asset.InMemoryAssetStorage;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.client.AiClient;
import com.lumi.vault.business.agent.client.ImageAnalysis;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.entity.AssetFeedback;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.mapper.AssetFeedbackMapper;
import com.lumi.vault.business.agent.service.AssetEnrichmentService;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.asset.storage.AssetStorage;
import com.lumi.vault.model.FakeImageModelClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "lumina.ai.max-image-bytes=2048")
@AutoConfigureMockMvc
@Transactional
class AgentFlowTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FakeAiClient aiClient;

    @Autowired
    AssetStorage assetStorage;

    @Autowired
    AssetEmbeddingMapper embeddingMapper;

    @Autowired
    AssetEmbeddingCache embeddingCache;

    @Autowired
    AssetFeedbackMapper feedbackMapper;

    @Autowired
    AssetEnrichmentService enrichmentService;

    @Autowired
    FakeImageModelClient imageModelClient;

    @Autowired
    AssetMapper assetMapper;

    @Autowired
    ObjectMapper objectMapper;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakesConfiguration {

        @Bean
        @Primary
        FakeAiClient fakeAiClient() {
            return new FakeAiClient();
        }

        @Bean
        @Primary
        FakeImageModelClient fakeImageModelClient() {
            return new FakeImageModelClient();
        }

        @Bean
        @Primary
        AssetStorage inMemoryAssetStorage() {
            return new InMemoryAssetStorage();
        }
    }

    @BeforeEach
    void setUp() {
        aiClient.setAvailable(true);
        aiClient.setVision(true);
        aiClient.setEmbedding(true);
        aiClient.setCompletion("{\"comment\":\"fake\",\"results\":[]}");
        aiClient.setImageCompletion(null);
        aiClient.setImageAnalysis(ImageAnalysis.of("测试图片描述", List.of("测试", "图片")));
        imageModelClient.setAvailable(true);
        imageModelClient.setRerankScores(null);
        imageModelClient.setClipEmbedding(null);
        imageModelClient.setClipTextEmbedding(null);
        ((InMemoryAssetStorage) assetStorage).clear();
    }

    @Test
    void enrichmentStoresAnalysisAndEmbedding() throws Exception {
        String token = register();
        long assetId = upload(token, "photo.png", pngBytes(40, 30));

        enrichmentService.enrich(assetId);

        mockMvc.perform(get("/api/agent/analysis/" + assetId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.description").value("测试图片描述"))
                .andExpect(jsonPath("$.data.tags[0]").value("测试"));
        assertThat(embeddingMapper.selectById(assetId).getEmbedding()).isNotBlank();
    }

    @Test
    void semanticSearchReturnsSha256WithConfidence() throws Exception {
        String token = register();
        long seaId = upload(token, "sea.png", pngBytes(40, 30));
        long mountainId = upload(token, "mountain.png", pngBytes(41, 30));
        insertEmbedding(seaId, "海边日落", List.of("海", "日落"), "海边日落");
        insertEmbedding(mountainId, "高山流水", List.of("山", "流水"), "高山流水");

        MvcResult result = mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"海边"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(seaId))
                .andExpect(jsonPath("$.data.results[0].sha256").isNotEmpty())
                .andExpect(jsonPath("$.data.results[1].assetId").value(mountainId))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        double first = ((Number) JsonPath.read(body, "$.data.results[0].confidence")).doubleValue();
        double second = ((Number) JsonPath.read(body, "$.data.results[1].confidence")).doubleValue();
        assertThat(first).isGreaterThan(second);
    }

    @Test
    void feedbackBoostsUserSelection() throws Exception {
        String token = register();
        long seaId = upload(token, "sea.png", pngBytes(40, 30));
        long mountainId = upload(token, "mountain.png", pngBytes(41, 30));
        insertEmbedding(seaId, "海边日落", List.of("海", "日落"), "海边日落");
        insertEmbedding(mountainId, "高山流水", List.of("山", "流水"), "高山流水");

        mockMvc.perform(post("/api/agent/feedback")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetId":%d,"action":"SELECT","query":"海和山"}
                                """.formatted(mountainId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"海和山"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(mountainId));
    }

    @Test
    void publicShortLinkRedirectsWithoutAuth() throws Exception {
        String token = register();
        long assetId = upload(token, "short-link.png", pngBytes(28, 28));

        mockMvc.perform(get("/f/" + assetId))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("download=false")));

        mockMvc.perform(get("/f/" + assetId + "/download"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("download=true")));

        mockMvc.perform(get("/f/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void purgeRemovesEmbeddingAndFeedback() throws Exception {
        String token = register();
        long assetId = upload(token, "purge-me.png", pngBytes(32, 32));
        enrichmentService.enrich(assetId);
        assertThat(embeddingMapper.selectById(assetId)).isNotNull();

        mockMvc.perform(post("/api/agent/feedback")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetId":%d,"action":"SELECT","query":"purge"}
                                """.formatted(assetId)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/assets/" + assetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/assets/" + assetId + "/purge")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        assertThat(embeddingMapper.selectById(assetId)).isNull();
        assertThat(feedbackMapper.selectCount(Wrappers.<AssetFeedback>lambdaQuery()
                .eq(AssetFeedback::getAssetId, assetId))).isZero();
        assertThat(((InMemoryAssetStorage) assetStorage).objectCount()).isZero();
    }

    @Test
    void enrichPendingQueuesAssets() throws Exception {
        String token = register();
        upload(token, "pending-1.png", pngBytes(50, 30));
        upload(token, "pending-2.png", pngBytes(51, 30));

        mockMvc.perform(post("/api/agent/enrich/pending")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(greaterThanOrEqualTo(2)));
    }

    @Test
    void searchFallsBackToKeywordWhenAiDisabled() throws Exception {
        String token = register();
        long assetId = upload(token, "sunset-beach.png", pngBytes(30, 30));
        aiClient.setAvailable(false);

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"sunset"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiEnabled").value(false))
                .andExpect(jsonPath("$.data.results[0].assetId").value(assetId));
    }

    @Test
    void tagRouteMatchesEvenWhenAiDisabled() throws Exception {
        String token = register();
        long seaId = upload(token, "tag-sea.png", pngBytes(40, 30));
        long mountainId = upload(token, "tag-mountain.png", pngBytes(41, 30));
        insertEmbedding(seaId, "海浪", List.of("sea"), "text-sea");
        insertEmbedding(mountainId, "山峰", List.of("mountain"), "text-mountain");
        aiClient.setAvailable(false);

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"sea"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiEnabled").value(false))
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].assetId").value(seaId))
                .andExpect(jsonPath("$.data.keywords[0]").value("sea"));
    }

    @Test
    void negativeTermsExcludeAssets() throws Exception {
        String token = register();
        long seaId = upload(token, "negative-sea.png", pngBytes(42, 30));
        long mountainId = upload(token, "negative-mountain.png", pngBytes(43, 30));
        insertEmbedding(seaId, "海浪", List.of("sea"), "text-sea");
        insertEmbedding(mountainId, "山峰", List.of("mountain"), "text-mountain");
        aiClient.setAvailable(false);

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"sea -mountain"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].assetId").value(seaId))
                .andExpect(jsonPath("$.data.excluded[0]").value("mountain"));
    }

    @Test
    void colorRouteMatchesStoredPalette() throws Exception {
        String token = register();
        long purpleId = upload(token, "palette-purple.png", pngBytes(44, 30));
        long greenId = upload(token, "palette-green.png", pngBytes(45, 30));
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, purpleId).set(Asset::getColors, "purple,white"));
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, greenId).set(Asset::getColors, "green"));
        aiClient.setAvailable(false);

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"紫色的图"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].assetId").value(purpleId))
                .andExpect(jsonPath("$.data.colors[0]").value("purple"));
    }

    @Test
    void localRerankReordersCandidates() throws Exception {
        String token = register();
        long seaId = upload(token, "rerank-sea.png", pngBytes(46, 30));
        long mountainId = upload(token, "rerank-mountain.png", pngBytes(47, 30));
        insertEmbedding(seaId, "海边日落", List.of("海", "日落"), "海边日落");
        insertEmbedding(mountainId, "高山流水", List.of("山", "流水"), "高山流水");
        imageModelClient.setRerankScores(List.of(0.1, 0.9));

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"海边"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(mountainId))
                .andExpect(jsonPath("$.data.results[1].assetId").value(seaId));
    }

    @Test
    void evalEndpointReflectsFeedbackClicks() throws Exception {
        String token = register();
        long seaId = upload(token, "eval-sea.png", pngBytes(48, 30));
        long mountainId = upload(token, "eval-mountain.png", pngBytes(49, 30));
        insertEmbedding(seaId, "海边日落", List.of("海", "日落"), "海边日落");
        insertEmbedding(mountainId, "高山流水", List.of("山", "流水"), "高山流水");

        mockMvc.perform(post("/api/agent/feedback")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetId":%d,"action":"SELECT","query":"海边"}
                                """.formatted(seaId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/agent/eval").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pairs").value(1))
                .andExpect(jsonPath("$.data.details[0].rank").value(1))
                .andExpect(jsonPath("$.data.mrrAt10", greaterThanOrEqualTo(0.99)));
    }

    @Test
    void searchHistoryRecordsAndDedupes() throws Exception {
        String token = register();
        long assetId = upload(token, "history.png", pngBytes(50, 31));
        insertEmbedding(assetId, "海边", List.of("海"), "海边");

        for (String query : List.of("海边", "海边", "山")) {
            mockMvc.perform(post("/api/agent/search")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"query":"%s"}
                                    """.formatted(query)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/agent/history").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].query").value("山"))
                .andExpect(jsonPath("$.data[1].query").value("海边"));

        mockMvc.perform(delete("/api/agent/history").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agent/history").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void favoritesSaveAndDelete() throws Exception {
        String token = register();
        long first = upload(token, "fav-a.png", pngBytes(52, 31));
        long second = upload(token, "fav-b.png", pngBytes(53, 31));

        MvcResult created = mockMvc.perform(post("/api/agent/favorites")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"测试收藏\",\"assetIds\":[" + first + "," + second + "," + first + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").value("测试收藏"))
                .andExpect(jsonPath("$.data.assetIds.length()").value(2))
                .andReturn();
        Number favoriteId = JsonPath.read(created.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");

        mockMvc.perform(get("/api/agent/favorites").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assetIds.length()").value(2));

        mockMvc.perform(delete("/api/agent/favorites/" + favoriteId.longValue())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agent/favorites").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void oversizeImageIsAnalyzedInsteadOfSkipped() throws Exception {
        String token = register();
        long assetId = upload(token, "oversize.png", pngBytes(3000, 2000));
        enrichmentService.enrich(assetId);

        mockMvc.perform(get("/api/agent/analysis/" + assetId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.description").value("测试图片描述"));
    }

    @Test
    void orientationFilterNarrowsResults() throws Exception {
        String token = register();
        long landscapeId = upload(token, "filter-landscape.png", pngBytes(60, 30));
        long portraitId = upload(token, "filter-portrait.png", pngBytes(30, 60));
        aiClient.setAvailable(false);

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"横屏的图"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].assetId").value(landscapeId))
                .andExpect(jsonPath("$.data.filters[0]").value("横屏"));
    }

    @Test
    void llmIntentParsingDrivesRetrieval() throws Exception {
        String token = register();
        long seaId = upload(token, "intent-llm-sea.png", pngBytes(72, 40));
        long mountainId = upload(token, "intent-llm-mountain.png", pngBytes(73, 40));
        insertEmbedding(seaId, "海边日落", List.of("sea"), "海边日落");
        insertEmbedding(mountainId, "高山流水", List.of("mountain"), "高山流水");
        aiClient.setCompletion("{\"concepts\":[],\"positive_tags\":[\"sea\"],\"rewrite\":\"海边风景\"}");

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"看看浪花"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(seaId))
                .andExpect(jsonPath("$.data.keywords[0]").value("sea"));
    }

    @Test
    void visionVerificationReordersCandidates() throws Exception {
        String token = register();
        long seaId = upload(token, "verify-sea.png", pngBytes(74, 40));
        long mountainId = upload(token, "verify-mountain.png", pngBytes(75, 40));
        insertEmbedding(seaId, "海边日落", List.of("海", "日落"), "海边日落");
        insertEmbedding(mountainId, "高山流水", List.of("山", "流水"), "高山流水");
        aiClient.setImageCompletion("{\"comment\":\"视觉确认完成\",\"results\":[{\"id\":" + mountainId
                + ",\"confidence\":0.99},{\"id\":" + seaId + ",\"confidence\":0.01}]}");

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"海边"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(mountainId))
                .andExpect(jsonPath("$.data.results[0].verified").value(true))
                .andExpect(jsonPath("$.data.comment").value("视觉确认完成"));
    }

    @Test
    void resultsAboveThresholdWithoutVisionVerificationAreMarked() throws Exception {
        String token = register();
        long seaId = upload(token, "unverified-sea.png", pngBytes(78, 40));
        insertEmbedding(seaId, "海边日落", List.of("海", "日落"), "海边日落");
        aiClient.setVision(false);

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"海边","minConfidence":0.3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].verified").value(false));
    }

    @Test
    void confidenceThresholdFiltersResults() throws Exception {
        String token = register();
        long seaId = upload(token, "threshold-sea.png", pngBytes(76, 40));
        long mountainId = upload(token, "threshold-mountain.png", pngBytes(77, 40));
        insertEmbedding(seaId, "海边日落", List.of("海", "日落"), "海边日落");
        insertEmbedding(mountainId, "高山流水", List.of("山", "流水"), "高山流水");

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"海边","minConfidence":0.999}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].assetId").value(seaId));

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"海边"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(2));
    }

    @Test
    void searchStreamEmitsRealProgressEvents() throws Exception {
        String token = register();

        MvcResult result = mockMvc.perform(post("/api/agent/search/stream")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"海边日落"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/event-stream")))
                .andReturn();

        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("event:progress");
        assertThat(content).contains("\"stage\":\"analyze\"");
        assertThat(content).contains("\"stage\":\"retrieve\"");
        assertThat(content).contains("\"stage\":\"done\"");
        assertThat(content).contains("event:result");
    }

    @Test
    void hairColorContradictionLowersConfidence() throws Exception {
        String token = register();
        long white = upload(token, "hair-white.png", pngBytes(60, 30));
        long pink = upload(token, "hair-pink.png", pngBytes(61, 30));
        insertEmbedding(white, "一位白发少女坐在窗边", List.of("white_hair", "1girl"), "白发少女");
        insertEmbedding(pink, "一位粉发少女坐在窗边", List.of("pink_hair", "1girl"), "粉发少女");

        MvcResult result = mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"白头发的女孩子"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(white))
                .andReturn();

        Number whiteConfidence = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.results[0].confidence");
        Number pinkConfidence = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.results[1].confidence");
        assertThat(whiteConfidence.doubleValue()).isGreaterThan(0);
        assertThat(pinkConfidence.doubleValue()).isLessThan(whiteConfidence.doubleValue() * 0.5);
    }

    @Test
    void searchWithPastedImageFindsVisuallySimilar() throws Exception {
        String token = register();
        long target = upload(token, "paste-target.png", pngBytes(80, 40));
        long other = upload(token, "paste-other.png", pngBytes(81, 40));
        insertEmbedding(target, "一只小狗", List.of("dog"), "dog");
        insertEmbedding(other, "一只小猫", List.of("cat"), "cat");
        attachImageVectors(target, new double[]{1, 0, 0}, new double[]{1, 0, 0});
        attachImageVectors(other, new double[]{0, 1, 0}, new double[]{0, 1, 0});
        imageModelClient.setEmbedding(List.of(1.0, 0.0, 0.0));
        imageModelClient.setClipEmbedding(List.of(1.0, 0.0, 0.0));

        MvcResult uploaded = mockMvc.perform(multipart("/api/agent/search/attachment")
                        .file(new MockMultipartFile("file", "pasted.png", "image/png", pngBytes(30, 30)))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        String attachmentId = JsonPath.read(
                uploaded.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.attachmentId");
        assertThat(attachmentId).isNotBlank();

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"","attachmentId":"%s"}
                                """.formatted(attachmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(target));
    }

    @Test
    void searchRejectsBlankQueryAndMissingAttachment() throws Exception {
        String token = register();

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":""}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"","attachmentId":"missing-id"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void subjectiveQueryUsesPerceptionProfile() throws Exception {
        String token = register();
        long warmId = upload(token, "warm.png", pngBytes(40, 30));
        long coldId = upload(token, "cold.png", pngBytes(41, 30));
        // 命中靠词典扩展（温馨 → 治愈），画像里有“治愈”的应排前
        insertEmbedding(warmId, "暖意", List.of("图"), "same", "{\"mood\":[\"治愈\"],\"wallpaperScore\":0.2}");
        insertEmbedding(coldId, "冷意", List.of("图"), "same", "{\"mood\":[\"暗黑\"],\"wallpaperScore\":0.2}");

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"温馨"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(warmId));
    }

    @Test
    void wallpaperQueryBoostsWallpaperScore() throws Exception {
        String token = register();
        long highId = upload(token, "high.png", pngBytes(40, 30));
        long lowId = upload(token, "low.png", pngBytes(41, 30));
        insertEmbedding(highId, "甲", List.of("图"), "same", "{\"wallpaperScore\":0.9}");
        insertEmbedding(lowId, "乙", List.of("图"), "same", "{\"wallpaperScore\":0.1}");

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"适合做壁纸"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(highId));
    }

    @Test
    void subjectiveQueryUsesClipTextRoute() throws Exception {
        String token = register();
        long clipId = upload(token, "clip-hit.png", pngBytes(42, 30));
        long otherId = upload(token, "clip-miss.png", pngBytes(43, 30));
        insertEmbedding(clipId, "同", List.of("图"), "same");
        insertEmbedding(otherId, "同", List.of("图"), "same");
        attachImageVectors(clipId, new double[]{0.9, 0.1}, new double[]{1.0, 0.0});
        attachImageVectors(otherId, new double[]{0.1, 0.9}, new double[]{0.0, 1.0});
        imageModelClient.setClipTextEmbedding(List.of(1.0, 0.0));

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"温馨"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(clipId));
    }

    @Test
    void enrichmentAggregatesPerceptionProfile() throws Exception {
        String token = register();
        long assetId = upload(token, "perception.png", pngBytes(40, 30));
        aiClient.setImageAnalysis(new ImageAnalysis("宁静的湖面", List.of("monochrome"),
                List.of("宁静"), List.of(), List.of(), List.of(), List.of()));

        enrichmentService.enrich(assetId);

        mockMvc.perform(get("/api/agent/analysis/" + assetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.perception.mood[0]").value("宁静"))
                .andExpect(jsonPath("$.data.perception.tone[0]").value("单色"));
    }

    @Test
    void perceptionBackfillFillsProfiles() throws Exception {
        String token = register();
        long assetId = upload(token, "backfill.png", pngBytes(60, 40));
        enrichmentService.enrich(assetId);
        AssetEmbedding row = embeddingMapper.selectById(assetId);
        assertThat(row.getPerception()).isNotNull();
        embeddingMapper.update(null, Wrappers.<AssetEmbedding>lambdaUpdate()
                .eq(AssetEmbedding::getAssetId, assetId)
                .set(AssetEmbedding::getPerception, null));
        embeddingCache.refresh(assetId);

        mockMvc.perform(post("/api/agent/perception/pending")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(greaterThanOrEqualTo(1)));

        assertThat(embeddingMapper.selectById(assetId).getPerception()).contains("wallpaperScore");
    }

    private void attachImageVectors(long assetId, double[] imageVector, double[] clipVector) {
        AssetEmbedding embedding = embeddingMapper.selectById(assetId);
        assertThat(embedding).isNotNull();
        embedding.setImageEmbedding(objectMapper.writeValueAsString(imageVector));
        embedding.setImageModel("wd14-image");
        embedding.setClipEmbedding(objectMapper.writeValueAsString(clipVector));
        embeddingMapper.updateById(embedding);
        embeddingCache.refresh(assetId);
    }

    private String register() throws Exception {
        String username = "agent_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"secret123"}
                                """.formatted(username)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.accessToken");
    }

    private long upload(String token, String filename, byte[] bytes) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/assets")
                        .file(new MockMultipartFile("file", filename, "image/png", bytes))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");
        return id.longValue();
    }

    private void insertEmbedding(long assetId, String description, List<String> tags, String embedText) {
        insertEmbedding(assetId, description, tags, embedText, null);
    }

    private void insertEmbedding(long assetId, String description, List<String> tags, String embedText,
                                 String perception) {
        AssetEmbedding embedding = new AssetEmbedding();
        embedding.setAssetId(assetId);
        embedding.setDescription(description);
        embedding.setTags(objectMapper.writeValueAsString(tags));
        embedding.setEmbedding(objectMapper.writeValueAsString(aiClient.embed(embedText)));
        embedding.setModel("fake");
        embedding.setPerception(perception);
        embedding.setStatus(AssetEmbedding.STATUS_DONE);
        embeddingMapper.insert(embedding);
        embeddingCache.refresh(assetId);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(120, 90, 200));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
