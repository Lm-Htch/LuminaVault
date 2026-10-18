package com.lumi.vault.graph;

import com.jayway.jsonpath.JsonPath;
import com.lumi.vault.agent.FakeAiClient;
import com.lumi.vault.asset.InMemoryAssetStorage;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.client.AiClient;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.asset.storage.AssetStorage;
import com.lumi.vault.business.graph.mapper.AssetSimilarityEdgeMapper;
import com.lumi.vault.business.graph.service.AssetSimilarityGraphService;
import com.lumi.vault.business.graph.service.GraphSettingsService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SimilarityGraphTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AssetStorage assetStorage;

    @Autowired
    AssetMapper assetMapper;

    @Autowired
    AssetEmbeddingMapper embeddingMapper;

    @Autowired
    AssetEmbeddingCache embeddingCache;

    @Autowired
    AssetSimilarityGraphService graphService;

    @Autowired
    AssetSimilarityEdgeMapper edgeMapper;

    @Autowired
    GraphSettingsService graphSettingsService;

    @Autowired
    ObjectMapper objectMapper;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakesConfiguration {

        @Bean
        @Primary
        AiClient fakeAiClient() {
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
        ((InMemoryAssetStorage) assetStorage).clear();
    }

    @Test
    void rebuildCreatesUndirectedEdgesAndPurgeCascades() throws Exception {
        String token = register();
        long a = upload(token, "graph-a.png", pngBytes(40, 30));
        long b = upload(token, "graph-b.png", pngBytes(41, 30));
        long c = upload(token, "graph-c.png", pngBytes(42, 30));
        insertVectors(a, new double[]{1, 0, 0});
        insertVectors(b, new double[]{0.99, 0.01, 0});
        insertVectors(c, new double[]{0, 1, 0});

        graphService.rebuildAll();

        assertThat(graphService.neighborsOf(a, 10)).extracting(AssetSimilarityGraphService.Neighbor::assetId)
                .containsExactly(b);
        // 无向：从 B 看同样能找到 A
        assertThat(graphService.neighborsOf(b, 10)).extracting(AssetSimilarityGraphService.Neighbor::assetId)
                .containsExactly(a);
        assertThat(graphService.neighborsOf(c, 10)).isEmpty();
        assertThat(graphService.edgeCount()).isEqualTo(1);

        mockMvc.perform(get("/api/agent/graph/stats")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edges").value(1));

        // 物理删除资产：先清依赖行（生产流程由事件监听清理），再删资产，边由外键级联清除
        embeddingMapper.deleteById(b);
        assetMapper.deleteById(b);
        assertThat(graphService.edgeCount()).isZero();
    }

    @Test
    void searchSuppressesNearDuplicates() throws Exception {
        String token = register();
        long a = upload(token, "dup-a.png", pngBytes(43, 30));
        long b = upload(token, "dup-b.png", pngBytes(44, 30));
        long c = upload(token, "dup-c.png", pngBytes(45, 30));
        insertEmbedding(a, "海边日落", List.of("sea"), "海边");
        insertEmbedding(b, "海边日落", List.of("sea"), "海边");
        insertEmbedding(c, "海边日落", List.of("sea"), "海边");
        insertVectors(a, new double[]{1, 0, 0});
        insertVectors(b, new double[]{0.99, 0.01, 0});
        insertVectors(c, new double[]{0, 1, 0});

        graphService.rebuildAll();

        MvcResult result = mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"海边"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(2))
                .andReturn();

        List<Integer> ids = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.results[*].assetId");
        assertThat(ids).containsExactly((int) a, (int) c);
    }

    @Test
    void feedbackPropagatesAlongGraph() throws Exception {
        String token = register();
        long a = upload(token, "fb-a.png", pngBytes(46, 30));
        long c = upload(token, "fb-c.png", pngBytes(47, 30));
        long b = upload(token, "fb-b.png", pngBytes(48, 30));
        // A 与查询无关但被点赞；B 与 A 强边；C 与查询同分（id 更小，无传播时会排前）
        insertEmbedding(a, "城市夜景", List.of("city"), "城市夜景");
        insertEmbedding(c, "海边日落", List.of("sea"), "海边");
        insertEmbedding(b, "海边日落", List.of("sea"), "海边");
        insertVectors(a, new double[]{1, 0, 0});
        insertVectors(c, new double[]{0, 1, 0});
        insertVectors(b, new double[]{0.99, 0.01, 0});
        // 填充资产：占据图路种子头部，让 A 不再成为图路种子，隔离出"反馈传播"这一条通路
        for (int i = 0; i < 22; i++) {
            long filler = upload(token, "fb-fill-" + i + ".png", pngBytes(60 + i, 30));
            insertEmbedding(filler, "海边日落", List.of("sea"), "海边");
            insertVectors(filler, new double[]{0, 0, 1});
        }

        graphService.rebuildAll();

        mockMvc.perform(post("/api/agent/feedback")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetId":%d,"action":"SELECT","query":"城市夜景"}
                                """.formatted(a)))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"海边"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        List<Integer> ids = JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.results[*].assetId");
        int indexB = ids.indexOf((int) b);
        int indexC = ids.indexOf((int) c);
        assertThat(indexB).isGreaterThanOrEqualTo(0);
        assertThat(indexC).isGreaterThanOrEqualTo(0);
        // B 与点赞资产 A 有强边：正反馈传播后应排在同分（id 更小）的 C 前面
        assertThat(indexB).isLessThan(indexC);
    }

    @Test
    void relatedEndpointReturnsNeighbors() throws Exception {
        String token = register();
        long a = upload(token, "rel-a.png", pngBytes(49, 30));
        long b = upload(token, "rel-b.png", pngBytes(50, 30));
        insertVectors(a, new double[]{1, 0, 0});
        insertVectors(b, new double[]{0.98, 0.02, 0});

        graphService.rebuildAll();

        mockMvc.perform(get("/api/assets/" + a + "/related")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assetId").value(b));
    }

    @Test
    void graphSettingsOverrideAndResetAffectsBuild() throws Exception {
        String token = register();
        long a = upload(token, "set-a.png", pngBytes(70, 30));
        long b = upload(token, "set-b.png", pngBytes(71, 30));
        insertVectors(a, new double[]{1, 0, 0});
        insertVectors(b, new double[]{0.6, 0.8, 0});
        // 双通道融合分 0.8：默认阈值 0.72 下应成边
        graphService.rebuildAll();
        assertThat(graphService.edgeCount()).isEqualTo(1);

        try {
            mockMvc.perform(get("/api/agent/graph/settings")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.effective.edgeThreshold").value(0.72))
                    .andExpect(jsonPath("$.data.defaults.edgeThreshold").value(0.72));

            mockMvc.perform(put("/api/agent/graph/settings")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"edgeThreshold":0.9}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.effective.edgeThreshold").value(0.9))
                    .andExpect(jsonPath("$.data.defaults.edgeThreshold").value(0.72));

            // 阈值即时生效：0.8 的边被剔除
            graphService.rebuildEdges(a);
            graphService.rebuildEdges(b);
            assertThat(graphService.edgeCount()).isZero();

            mockMvc.perform(delete("/api/agent/graph/settings")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.effective.edgeThreshold").value(0.72));

            graphService.rebuildEdges(a);
            graphService.rebuildEdges(b);
            assertThat(graphService.edgeCount()).isEqualTo(1);

            mockMvc.perform(put("/api/agent/graph/settings")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"edgeThreshold":1.5}
                                    """))
                    .andExpect(status().isBadRequest());
        } finally {
            graphSettingsService.reset();
        }
    }

    private void insertEmbedding(long assetId, String description, List<String> tags, String embedText) {
        AssetEmbedding embedding = embeddingMapper.selectById(assetId);
        if (embedding == null) {
            embedding = new AssetEmbedding();
            embedding.setAssetId(assetId);
            embedding.setStatus(AssetEmbedding.STATUS_DONE);
            embedding.setModel("fake");
            embedding.setDescription(description);
            embedding.setTags(objectMapper.writeValueAsString(tags));
            embeddingMapper.insert(embedding);
        } else {
            embedding.setDescription(description);
            embedding.setTags(objectMapper.writeValueAsString(tags));
            embeddingMapper.updateById(embedding);
        }
        embeddingCache.refresh(assetId);
    }

    private void insertVectors(long assetId, double[] vector) {
        AssetEmbedding embedding = embeddingMapper.selectById(assetId);
        if (embedding == null) {
            embedding = new AssetEmbedding();
            embedding.setAssetId(assetId);
            embedding.setStatus(AssetEmbedding.STATUS_DONE);
            embedding.setModel("fake");
            embedding.setImageEmbedding(objectMapper.writeValueAsString(vector));
            embedding.setImageModel("wd14-image");
            embedding.setClipEmbedding(objectMapper.writeValueAsString(vector));
            embeddingMapper.insert(embedding);
        } else {
            embedding.setImageEmbedding(objectMapper.writeValueAsString(vector));
            embedding.setImageModel("wd14-image");
            embedding.setClipEmbedding(objectMapper.writeValueAsString(vector));
            embeddingMapper.updateById(embedding);
        }
        embeddingCache.refresh(assetId);
    }

    private String register() throws Exception {
        String username = "graph_" + UUID.randomUUID().toString().substring(0, 8);
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

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(90, 60, 140));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
