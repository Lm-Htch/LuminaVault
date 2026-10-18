package com.lumi.vault.asset;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.asset.service.AssetService;
import com.lumi.vault.business.asset.storage.AssetStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class AssetFlowTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AssetStorage assetStorage;

    @Autowired
    AssetMapper assetMapper;

    @Autowired
    AssetService assetService;

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {

        @Bean
        @Primary
        AssetStorage inMemoryAssetStorage() {
            return new InMemoryAssetStorage();
        }
    }

    @BeforeEach
    void resetStorage() {
        ((InMemoryAssetStorage) assetStorage).clear();
    }

    @Test
    void uploadListDetailAndUpdate() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/assets")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", pngBytes(120, 80))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.originalFilename").value("photo.png"))
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.width").value(120))
                .andExpect(jsonPath("$.data.height").value(80))
                .andExpect(jsonPath("$.data.sha256").isNotEmpty())
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                .andReturn();

        long id = readLong(uploaded, "$.data.id");
        String createdAt = readString(uploaded, "$.data.createdAt");
        String uploadedAt = readString(uploaded, "$.data.updatedAt");

        mockMvc.perform(get("/api/assets").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(id));

        mockMvc.perform(get("/api/assets/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").isNotEmpty())
                .andExpect(jsonPath("$.data.thumbnailUrl").isNotEmpty());

        mockMvc.perform(get("/api/assets/" + id + "/url").param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

        MvcResult updated = mockMvc.perform(put("/api/assets/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalFilename":"renamed.png","description":"日落"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename").value("renamed.png"))
                .andExpect(jsonPath("$.data.description").value("日落"))
                .andExpect(jsonPath("$.data.createdAt").value(createdAt))
                .andReturn();
        assertThat(readString(updated, "$.data.updatedAt")).isNotEqualTo(uploadedAt);
    }

    @Test
    void uploadRejectsDuplicateAndNonImage() throws Exception {
        byte[] bytes = pngBytes(60, 60);
        mockMvc.perform(multipart("/api/assets")
                        .file(new MockMultipartFile("file", "a.png", "image/png", bytes)))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/assets")
                        .file(new MockMultipartFile("file", "b.png", "image/png", bytes)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        mockMvc.perform(multipart("/api/assets")
                        .file(new MockMultipartFile("file", "c.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteTrashRestorePurgeFlow() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/assets")
                        .file(new MockMultipartFile("file", "trash.png", "image/png", pngBytes(50, 40))))
                .andExpect(status().isCreated())
                .andReturn();
        long id = readLong(uploaded, "$.data.id");

        mockMvc.perform(delete("/api/assets/" + id)).andExpect(status().isOk());
        mockMvc.perform(get("/api/assets")).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/assets/trash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].deletedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.records[0].purgeAt").isNotEmpty());

        mockMvc.perform(post("/api/assets/" + id + "/restore")).andExpect(status().isOk());
        mockMvc.perform(get("/api/assets")).andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(delete("/api/assets/" + id)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/assets/" + id + "/purge")).andExpect(status().isOk());
        mockMvc.perform(get("/api/assets/trash")).andExpect(jsonPath("$.data.total").value(0));
        assertThat(((InMemoryAssetStorage) assetStorage).objectCount()).isZero();
    }

    @Test
    void batchDeleteRestorePurgeFlow() throws Exception {
        long a1 = uploadAsset("batch-a.png", pngBytes(51, 40));
        long a2 = uploadAsset("batch-b.png", pngBytes(52, 40));

        mockMvc.perform(post("/api/assets/batch/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetIds\":[" + a1 + "," + a2 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));
        mockMvc.perform(get("/api/assets/trash")).andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(post("/api/assets/batch/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetIds\":[" + a1 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
        mockMvc.perform(get("/api/assets")).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/assets/trash")).andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(post("/api/assets/batch/purge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetIds\":[" + a1 + "," + a2 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
        mockMvc.perform(get("/api/assets/trash")).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/assets")).andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void settingsCanBeUpdated() throws Exception {
        mockMvc.perform(get("/api/assets/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retentionDays").value(30));
        mockMvc.perform(put("/api/assets/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"retentionDays":7}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retentionDays").value(7));
        mockMvc.perform(put("/api/assets/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"retentionDays":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void purgeExpiredRemovesAssetsPastRetention() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/assets")
                        .file(new MockMultipartFile("file", "expired.png", "image/png", pngBytes(30, 30))))
                .andExpect(status().isCreated())
                .andReturn();
        long id = readLong(uploaded, "$.data.id");
        mockMvc.perform(delete("/api/assets/" + id)).andExpect(status().isOk());

        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, id)
                .set(Asset::getDeletedAt, Instant.now().minus(Duration.ofDays(31))));

        int purged = assetService.purgeExpired();

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(assetMapper.selectById(id)).isNull();
        assertThat(((InMemoryAssetStorage) assetStorage).objectCount()).isZero();
    }

    @Test
    void searchByFilenameExactAndFuzzy() throws Exception {
        uploadAsset("Sunset-Beach.png", pngBytes(22, 22));

        mockMvc.perform(get("/api/assets/search").param("filename", "sunset-beach.png").param("exact", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/assets/search").param("filename", "beach"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/assets/search").param("filename", "sunset").param("exact", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void searchByAuthorExactAndFuzzy() throws Exception {
        long id = uploadAsset("author-test.png", pngBytes(23, 23));

        mockMvc.perform(put("/api/assets/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"author":"Alice"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.author").value("Alice"));

        mockMvc.perform(get("/api/assets/search").param("author", "alice").param("exact", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/assets/search").param("author", "ALI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/assets/search").param("author", "Bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void metadataIsExtractedAndPersisted() throws Exception {
        long id = uploadAsset("meta.png", pngBytes(25, 25));

        mockMvc.perform(get("/api/assets/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metadata").isNotEmpty())
                .andExpect(jsonPath("$.data.metadata.attributes").isNotEmpty());

        Asset stored = assetMapper.selectById(id);
        assertThat(stored.getMetadata()).isNotNull();
        assertThat(stored.getMetadata().attributes()).isNotEmpty();
    }

    @Test
    void listSupportsCustomSorting() throws Exception {
        long zeta = uploadAsset("sort-zeta.png", noisyPngBytes(70, 30));
        long alpha = uploadAsset("sort-alpha.png", noisyPngBytes(80, 30));
        long mid = uploadAsset("sort-mid.png", noisyPngBytes(90, 30));

        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(alpha))
                .andExpect(jsonPath("$.data.records[1].id").value(mid))
                .andExpect(jsonPath("$.data.records[2].id").value(zeta));

        mockMvc.perform(get("/api/assets").param("sort", "name_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(zeta))
                .andExpect(jsonPath("$.data.records[1].id").value(mid))
                .andExpect(jsonPath("$.data.records[2].id").value(alpha));

        mockMvc.perform(get("/api/assets").param("sort", "created_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(mid))
                .andExpect(jsonPath("$.data.records[1].id").value(alpha))
                .andExpect(jsonPath("$.data.records[2].id").value(zeta));

        mockMvc.perform(get("/api/assets").param("sort", "created_asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(zeta))
                .andExpect(jsonPath("$.data.records[1].id").value(alpha))
                .andExpect(jsonPath("$.data.records[2].id").value(mid));

        mockMvc.perform(get("/api/assets").param("sort", "size_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(mid))
                .andExpect(jsonPath("$.data.records[1].id").value(alpha))
                .andExpect(jsonPath("$.data.records[2].id").value(zeta));

        mockMvc.perform(get("/api/assets").param("sort", "unknown-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(alpha));

        mockMvc.perform(get("/api/assets/search").param("sort", "name_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(zeta))
                .andExpect(jsonPath("$.data.records[1].id").value(mid))
                .andExpect(jsonPath("$.data.records[2].id").value(alpha));
    }

    @Test
    void filenameSortIsNaturalNumericOrder() throws Exception {
        long f109 = uploadAsset("109.jpg", pngBytes(31, 30));
        long f110 = uploadAsset("110.jpg", pngBytes(32, 30));
        long f1094 = uploadAsset("1094.jpg", pngBytes(33, 30));
        long img2 = uploadAsset("img 2.png", pngBytes(34, 30));
        long img10 = uploadAsset("img 10.png", pngBytes(35, 30));

        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(f109))
                .andExpect(jsonPath("$.data.records[1].id").value(f110))
                .andExpect(jsonPath("$.data.records[2].id").value(f1094))
                .andExpect(jsonPath("$.data.records[3].id").value(img2))
                .andExpect(jsonPath("$.data.records[4].id").value(img10));

        mockMvc.perform(get("/api/assets").param("sort", "name_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(img10))
                .andExpect(jsonPath("$.data.records[1].id").value(img2))
                .andExpect(jsonPath("$.data.records[2].id").value(f1094))
                .andExpect(jsonPath("$.data.records[3].id").value(f110))
                .andExpect(jsonPath("$.data.records[4].id").value(f109));

        mockMvc.perform(put("/api/assets/" + f110)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalFilename":"img 0.png"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(f109))
                .andExpect(jsonPath("$.data.records[1].id").value(f1094))
                .andExpect(jsonPath("$.data.records[2].id").value(f110))
                .andExpect(jsonPath("$.data.records[3].id").value(img2))
                .andExpect(jsonPath("$.data.records[4].id").value(img10));
    }

    private long uploadAsset(String filename, byte[] bytes) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/assets")
                        .file(new MockMultipartFile("file", filename, "image/png", bytes)))
                .andExpect(status().isCreated())
                .andReturn();
        return readLong(result, "$.data.id");
    }

    private long readLong(MvcResult result, String path) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
        return value.longValue();
    }

    private String readString(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    private byte[] noisyPngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(30, 120, 200));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
