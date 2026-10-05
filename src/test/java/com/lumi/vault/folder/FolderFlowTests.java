package com.lumi.vault.folder;

import com.jayway.jsonpath.JsonPath;
import com.lumi.vault.agent.FakeAiClient;
import com.lumi.vault.asset.InMemoryAssetStorage;
import com.lumi.vault.business.agent.service.AssetEnrichmentService;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.asset.storage.AssetStorage;
import com.lumi.vault.business.folder.entity.Folder;
import com.lumi.vault.business.folder.mapper.FolderMapper;
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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
class FolderFlowTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FakeAiClient aiClient;

    @Autowired
    AssetStorage assetStorage;

    @Autowired
    AssetMapper assetMapper;

    @Autowired
    FolderMapper folderMapper;

    @Autowired
    AssetEnrichmentService enrichmentService;

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
        aiClient.setCompletion("{\"comment\":\"fake\",\"results\":[]}");
        ((InMemoryAssetStorage) assetStorage).clear();
    }

    @Test
    void folderCrudAndManualAssignment() throws Exception {
        String token = register();
        long folderId = createFolder(token, "风景", "自然风景与壁纸");

        long assetId = upload(token, "manual.png", pngBytes(40, 30), folderId);
        assertThat(assetMapper.selectById(assetId).getFolderId()).isEqualTo(folderId);

        mockMvc.perform(get("/api/folders").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("风景"))
                .andExpect(jsonPath("$.data[0].assetCount").value(1));

        mockMvc.perform(put("/api/folders/" + folderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"风景壁纸","description":"壁纸"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("风景壁纸"));

        mockMvc.perform(delete("/api/folders/" + folderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        assertThat(assetMapper.selectById(assetId).getFolderId()).isNull();
    }

    @Test
    void agentClassifiesIntoExistingFolder() throws Exception {
        String token = register();
        long folderId = createFolder(token, "风景", null);

        aiClient.setCompletion("{\"folderId\":" + folderId + ",\"newFolderName\":null}");
        long assetId = upload(token, "auto-existing.png", pngBytes(41, 30), null);
        enrichmentService.enrich(assetId);

        assertThat(assetMapper.selectById(assetId).getFolderId()).isEqualTo(folderId);
    }

    @Test
    void agentCreatesNewFolderWhenNoMatch() throws Exception {
        String token = register();
        aiClient.setCompletion("{\"folderId\":null,\"newFolderName\":\"UI素材\"}");
        long assetId = upload(token, "auto-new.png", pngBytes(42, 30), null);
        enrichmentService.enrich(assetId);

        Asset stored = assetMapper.selectById(assetId);
        assertThat(stored.getFolderId()).isNotNull();
        Folder folder = folderMapper.selectById(stored.getFolderId());
        assertThat(folder.getName()).isEqualTo("UI素材");
        assertThat(folder.getAutoCreated()).isTrue();
    }

    @Test
    void batchAssignDeleteAndClassify() throws Exception {
        String token = register();
        long folderId = createFolder(token, "批量风景", null);
        long a1 = upload(token, "batch-1.png", pngBytes(43, 30), null);
        long a2 = upload(token, "batch-2.png", pngBytes(44, 30), null);
        enrichmentService.enrich(a1);

        mockMvc.perform(put("/api/folders/assets")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetIds\":[" + a1 + "," + a2 + "],\"folderId\":" + folderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));
        assertThat(assetMapper.selectById(a1).getFolderId()).isEqualTo(folderId);
        assertThat(assetMapper.selectById(a2).getFolderId()).isEqualTo(folderId);

        mockMvc.perform(post("/api/assets/batch/delete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetIds\":[" + a2 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
        assertThat(assetMapper.selectById(a2).getDeleted()).isTrue();

        aiClient.setCompletion("{\"folderId\":" + folderId + ",\"newFolderName\":null}");
        mockMvc.perform(post("/api/folders/classify")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetIds\":[" + a1 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
    }

    private long createFolder(String token, String name, String description) throws Exception {
        String body = description == null
                ? "{\"name\":\"" + name + "\"}"
                : "{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}";
        MvcResult result = mockMvc.perform(post("/api/folders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");
        return id.longValue();
    }

    private String register() throws Exception {
        String username = "folder_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"secret123"}
                                """.formatted(username)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.accessToken");
    }

    private long upload(String token, String filename, byte[] bytes, Long folderId) throws Exception {
        var request = multipart("/api/assets")
                .file(new MockMultipartFile("file", filename, "image/png", bytes))
                .header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (folderId != null) {
            request = request.param("folderId", String.valueOf(folderId));
        }
        MvcResult result = mockMvc.perform(request)
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
