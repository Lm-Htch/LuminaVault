package com.lumi.vault.asset;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class AssetSimilarityTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FakeImageModelClient imageModelClient;

    @Autowired
    AssetStorage assetStorage;

    @Autowired
    AssetEmbeddingMapper embeddingMapper;

    @Autowired
    AssetEmbeddingCache embeddingCache;

    @Autowired
    AssetMapper assetMapper;

    @Autowired
    ObjectMapper objectMapper;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakesConfiguration {

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
        imageModelClient.setAvailable(true);
        imageModelClient.setEmbedding(List.of(0.9, 0.2, 0.1));
        ((InMemoryAssetStorage) assetStorage).clear();
    }

    @Test
    void similarByAssetReturnsClosestFirst() throws Exception {
        String token = register();
        long first = upload(token, "sim-a.png", pngBytes(40, 30));
        long near = upload(token, "sim-b.png", pngBytes(41, 30));
        long far = upload(token, "sim-c.png", pngBytes(42, 30));
        insertImageEmbedding(first, new double[]{1, 0, 0});
        insertImageEmbedding(near, new double[]{0.9, 0.1, 0});
        insertImageEmbedding(far, new double[]{0, 1, 0});

        mockMvc.perform(get("/api/assets/" + first + "/similar")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].assetId").value(near))
                .andExpect(jsonPath("$.data[1].assetId").value(far));
    }

    @Test
    void similarByUploadedImageMatchesLibrary() throws Exception {
        String token = register();
        long target = upload(token, "sim-target.png", pngBytes(43, 30));
        long other = upload(token, "sim-other.png", pngBytes(44, 30));
        insertImageEmbedding(target, new double[]{1, 0, 0});
        insertImageEmbedding(other, new double[]{0, 1, 0});
        imageModelClient.setEmbedding(List.of(1.0, 0.0, 0.0));

        mockMvc.perform(multipart("/api/assets/similar-search")
                        .file(new MockMultipartFile("file", "probe.png", "image/png", pngBytes(30, 30)))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].assetId").value(target))
                .andExpect(jsonPath("$.data[1].assetId").value(other));
    }

    @Test
    void similarSkipsDeletedAssets() throws Exception {
        String token = register();
        long first = upload(token, "sim-d.png", pngBytes(45, 30));
        long second = upload(token, "sim-e.png", pngBytes(46, 30));
        insertImageEmbedding(first, new double[]{1, 0, 0});
        insertImageEmbedding(second, new double[]{0.8, 0.2, 0});
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, second)
                .set(Asset::getDeleted, true));

        mockMvc.perform(get("/api/assets/" + first + "/similar")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void similarReturnsEmptyWithoutImageVector() throws Exception {
        String token = register();
        long assetId = upload(token, "sim-f.png", pngBytes(47, 30));

        mockMvc.perform(get("/api/assets/" + assetId + "/similar")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void clipEmbeddingsAreSearchedWhenWd14Missing() throws Exception {
        String token = register();
        long first = upload(token, "clip-a.png", pngBytes(48, 30));
        long near = upload(token, "clip-b.png", pngBytes(49, 30));
        long far = upload(token, "clip-c.png", pngBytes(50, 30));
        insertImageEmbedding(first, null, new double[]{0, 1, 0});
        insertImageEmbedding(near, null, new double[]{0, 0.9, 0.1});
        insertImageEmbedding(far, null, new double[]{1, 0, 0});

        mockMvc.perform(get("/api/assets/" + first + "/similar")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].assetId").value(near))
                .andExpect(jsonPath("$.data[1].assetId").value(far));
    }

    @Test
    void fusionCombinesWd14AndClipRoutes() throws Exception {
        String token = register();
        long first = upload(token, "fusion-a.png", pngBytes(51, 30));
        long bothMatch = upload(token, "fusion-b.png", pngBytes(52, 30));
        long clipMatch = upload(token, "fusion-c.png", pngBytes(53, 30));
        insertImageEmbedding(first, new double[]{1, 0, 0}, new double[]{0, 1, 0});
        insertImageEmbedding(bothMatch, new double[]{0.95, 0.05, 0}, new double[]{0, 1, 0});
        insertImageEmbedding(clipMatch, new double[]{0, 1, 0}, new double[]{0, 0.95, 0.05});

        mockMvc.perform(get("/api/assets/" + first + "/similar")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].assetId").value(bothMatch))
                .andExpect(jsonPath("$.data[1].assetId").value(clipMatch));
    }

    @Test
    void tagOverlapBoostsSimilarResults() throws Exception {
        String token = register();
        long base = upload(token, "tag-a.png", pngBytes(54, 30));
        long tagMatch = upload(token, "tag-b.png", pngBytes(55, 30));
        long vectorNoise = upload(token, "tag-c.png", pngBytes(56, 30));
        insertEmbeddingWithTags(base, new double[]{1, 0, 0}, List.of("sea", "sunset", "bird", "hat"));
        insertEmbeddingWithTags(tagMatch, new double[]{0, 1, 0}, List.of("sea", "sunset", "bird", "hat"));
        insertEmbeddingWithTags(vectorNoise, new double[]{0.99, 0.01, 0}, List.of("city", "street"));

        mockMvc.perform(get("/api/assets/" + base + "/similar")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].assetId").value(tagMatch))
                .andExpect(jsonPath("$.data[1].assetId").value(vectorNoise));
    }

    @Test
    void tagSimilarityWorksWithoutVectors() throws Exception {
        String token = register();
        long base = upload(token, "tagv-a.png", pngBytes(57, 30));
        long tagMatch = upload(token, "tagv-b.png", pngBytes(58, 30));
        insertEmbeddingRaw(base, null, null, List.of("lantern", "night", "water"));
        insertEmbeddingRaw(tagMatch, null, null, List.of("lantern", "night", "dark"));

        mockMvc.perform(get("/api/assets/" + base + "/similar")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assetId").value(tagMatch));
    }

    private void insertImageEmbedding(long assetId, double[] vector) {
        insertImageEmbedding(assetId, vector, null);
    }

    private void insertImageEmbedding(long assetId, double[] imageVector, double[] clipVector) {
        insertEmbeddingRaw(assetId, imageVector, clipVector, null);
    }

    private void insertEmbeddingWithTags(long assetId, double[] imageVector, List<String> tags) {
        insertEmbeddingRaw(assetId, imageVector, null, tags);
    }

    private void insertEmbeddingRaw(long assetId, double[] imageVector, double[] clipVector, List<String> tags) {
        AssetEmbedding embedding = new AssetEmbedding();
        embedding.setAssetId(assetId);
        embedding.setStatus(AssetEmbedding.STATUS_DONE);
        if (imageVector != null) {
            embedding.setImageEmbedding(objectMapper.writeValueAsString(imageVector));
            embedding.setImageModel("wd14-image");
        }
        if (clipVector != null) {
            embedding.setClipEmbedding(objectMapper.writeValueAsString(clipVector));
        }
        if (tags != null) {
            embedding.setTags(objectMapper.writeValueAsString(tags));
        }
        embeddingMapper.insert(embedding);
        embeddingCache.refresh(assetId);
    }

    private String register() throws Exception {
        String username = "similar_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/register")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
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
        graphics.setColor(new Color(120, 90, 200));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
