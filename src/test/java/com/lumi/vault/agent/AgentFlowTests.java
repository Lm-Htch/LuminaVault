package com.lumi.vault.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lumi.vault.asset.InMemoryAssetStorage;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.client.AiClient;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.entity.AssetFeedback;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.mapper.AssetFeedbackMapper;
import com.lumi.vault.business.agent.service.AssetEnrichmentService;
import com.lumi.vault.business.asset.storage.AssetStorage;
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

@SpringBootTest
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
        AssetStorage inMemoryAssetStorage() {
            return new InMemoryAssetStorage();
        }
    }

    @BeforeEach
    void setUp() {
        aiClient.setAvailable(true);
        aiClient.setVision(true);
        aiClient.setEmbedding(true);
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
        AssetEmbedding embedding = new AssetEmbedding();
        embedding.setAssetId(assetId);
        embedding.setDescription(description);
        embedding.setTags(objectMapper.writeValueAsString(tags));
        embedding.setEmbedding(objectMapper.writeValueAsString(aiClient.embed(embedText)));
        embedding.setModel("fake");
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
