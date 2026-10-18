package com.lumi.vault.character;

import com.jayway.jsonpath.JsonPath;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.asset.InMemoryAssetStorage;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.service.AssetEnrichmentService;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.asset.storage.AssetStorage;
import com.lumi.vault.business.character.entity.CharacterReference;
import com.lumi.vault.business.character.mapper.CharacterReferenceMapper;
import com.lumi.vault.agent.FakeAiClient;
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
class CharacterFlowTests {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FakeAiClient aiClient;

    @Autowired
    FakeImageModelClient imageModelClient;

    @Autowired
    AssetStorage assetStorage;

    @Autowired
    AssetEnrichmentService enrichmentService;

    @Autowired
    AssetMapper assetMapper;

    @Autowired
    AssetEmbeddingMapper embeddingMapper;

    @Autowired
    AssetEmbeddingCache embeddingCache;

    @Autowired
    CharacterReferenceMapper referenceMapper;

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
        imageModelClient.setAvailable(true);
        imageModelClient.setTags(List.of());
        imageModelClient.setCharacters(List.of());
        imageModelClient.setEmbedding(List.of(0.9, 0.2, 0.1));
        imageModelClient.setModel("fake-image");
        ((InMemoryAssetStorage) assetStorage).clear();
    }

    @Test
    void createCharacterWithReferenceAndMark() throws Exception {
        String token = register();
        long assetId = upload(token, "miku.png", pngBytes(40, 30));
        enrichmentService.enrich(assetId);

        MvcResult created = mockMvc.perform(post("/api/characters")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"初音未来","aliases":["miku","hatsune miku"],"series":"VOCALOID","referenceAssetId":%d}
                                """.formatted(assetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("初音未来"))
                .andExpect(jsonPath("$.data.referenceCount").value(1))
                .andReturn();

        long characterId = readLong(created, "$.data.id");
        assertThat(readLong(created, "$.data.referenceCount")).isEqualTo(1);
        assertThat(assetMapper.selectById(assetId).getCharacterId()).isEqualTo(characterId);

        mockMvc.perform(get("/api/assets/" + assetId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.characterId").value(characterId));
    }

    @Test
    void enrichmentAutoMatchesKnownCharacter() throws Exception {
        String token = register();
        long referenceAsset = upload(token, "ref.png", pngBytes(40, 30));
        enrichmentService.enrich(referenceAsset);
        mockMvc.perform(post("/api/characters")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"初音未来","aliases":["miku"],"referenceAssetId":%d}
                                """.formatted(referenceAsset)))
                .andExpect(status().isOk());

        long newAsset = upload(token, "another.png", pngBytes(41, 30));
        enrichmentService.enrich(newAsset);

        Asset stored = assetMapper.selectById(newAsset);
        assertThat(stored.getCharacterId()).isNotNull();
        assertThat(stored.getCharacterConfidence()).isGreaterThan(0.9);
    }

    @Test
    void searchAliasBoostsCharacterAsset() throws Exception {
        String token = register();
        long characterAsset = upload(token, "character.png", pngBytes(42, 30));
        long otherAsset = upload(token, "other.png", pngBytes(43, 30));
        insertEmbedding(characterAsset, "角色形象", "海边日落");
        insertEmbedding(otherAsset, "其他图片", "海边日落");

        MvcResult created = mockMvc.perform(post("/api/characters")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"初音未来","aliases":["miku"],"referenceAssetId":%d}
                                """.formatted(characterAsset)))
                .andExpect(status().isOk())
                .andReturn();
        long characterId = readLong(created, "$.data.id");

        mockMvc.perform(post("/api/agent/search")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"miku 的图片"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].assetId").value(characterAsset))
                .andExpect(jsonPath("$.data.results[0].characterId").value(characterId))
                .andExpect(jsonPath("$.data.results[0].characterName").value("初音未来"));
    }

    @Test
    void enrichmentMatchesCharacterByModelTag() throws Exception {
        String token = register();
        mockMvc.perform(post("/api/characters")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"凝光","aliases":["ningguang"]}
                                """))
                .andExpect(status().isOk());

        imageModelClient.setCharacters(List.of("ningguang_(genshin_impact)"));
        long assetId = upload(token, "tagged.png", pngBytes(46, 30));
        enrichmentService.enrich(assetId);

        Asset stored = assetMapper.selectById(assetId);
        assertThat(stored.getCharacterId()).isNotNull();
        assertThat(stored.getCharacterConfidence()).isEqualTo(0.95);
    }

    @Test
    void referenceUsesImageEmbeddingModel() throws Exception {
        String token = register();
        long assetId = upload(token, "img-embed.png", pngBytes(47, 30));
        enrichmentService.enrich(assetId);

        MvcResult created = mockMvc.perform(post("/api/characters")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"参考模型测试","referenceAssetId":%d}
                                """.formatted(assetId)))
                .andExpect(status().isOk())
                .andReturn();
        long characterId = readLong(created, "$.data.id");

        List<CharacterReference> references = referenceMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<CharacterReference>lambdaQuery()
                        .eq(CharacterReference::getCharacterId, characterId));
        assertThat(references).hasSize(1);
        assertThat(references.get(0).getModel()).isEqualTo("fake-image");
    }

    @Test
    void bootstrapCreatesCharactersFromModelTags() throws Exception {
        String token = register();
        long assetId = upload(token, "bootstrap.png", pngBytes(48, 30));
        insertEmbedding(assetId, "测试", "角色形象描述");
        embeddingMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<AssetEmbedding>lambdaUpdate()
                .eq(AssetEmbedding::getAssetId, assetId)
                .set(AssetEmbedding::getCharacterTags, "[\"keqing_(genshin_impact)\"]"));

        mockMvc.perform(post("/api/characters/bootstrap")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));

        mockMvc.perform(get("/api/characters").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Keqing"))
                .andExpect(jsonPath("$.data[0].series").value("Genshin Impact"))
                .andExpect(jsonPath("$.data[0].autoCreated").value(true));

        mockMvc.perform(post("/api/characters/bootstrap")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    void autoCreateSettingCreatesCharacterDuringEnrichment() throws Exception {
        String token = register();
        mockMvc.perform(put("/api/characters/settings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"autoCreate":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoCreate").value(true));

        imageModelClient.setCharacters(List.of("keqing_(genshin_impact)"));
        long assetId = upload(token, "auto-create.png", pngBytes(49, 30));
        enrichmentService.enrich(assetId);

        Asset stored = assetMapper.selectById(assetId);
        assertThat(stored.getCharacterId()).isNotNull();
        mockMvc.perform(get("/api/characters").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Keqing"))
                .andExpect(jsonPath("$.data[0].autoCreated").value(true));
    }

    @Test
    void feedbackSelectAccumulatesReference() throws Exception {
        String token = register();
        long referenceAsset = upload(token, "ref.png", pngBytes(44, 30));
        long pickedAsset = upload(token, "picked.png", pngBytes(45, 30));
        insertEmbedding(referenceAsset, "角色形象", "角色形象描述");
        insertEmbedding(pickedAsset, "候选图片", "角色形象描述");

        mockMvc.perform(post("/api/characters")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"初音未来","aliases":["miku"],"referenceAssetId":%d}
                                """.formatted(referenceAsset)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/agent/feedback")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetId":%d,"action":"SELECT","query":"miku"}
                                """.formatted(pickedAsset)))
                .andExpect(status().isOk());

        List<CharacterReference> feedbackReferences = referenceMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<CharacterReference>lambdaQuery()
                        .eq(CharacterReference::getAssetId, pickedAsset)
                        .eq(CharacterReference::getSource, CharacterReference.SOURCE_FEEDBACK));
        assertThat(feedbackReferences).hasSize(1);
        assertThat(feedbackReferences.get(0).getWeight()).isLessThan(1.0);
        assertThat(assetMapper.selectById(pickedAsset).getCharacterId()).isNotNull();
    }

    @Test
    void batchMarkAssetsToCharacter() throws Exception {
        String token = register();
        MvcResult created = mockMvc.perform(post("/api/characters")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"批量角色"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long characterId = readLong(created, "$.data.id");
        long a1 = upload(token, "batch-a.png", pngBytes(60, 30));
        long a2 = upload(token, "batch-b.png", pngBytes(61, 30));

        mockMvc.perform(put("/api/characters/assets")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetIds\":[" + a1 + "," + a2 + "],\"characterId\":" + characterId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));
        assertThat(assetMapper.selectById(a1).getCharacterId()).isEqualTo(characterId);
        assertThat(assetMapper.selectById(a2).getCharacterId()).isEqualTo(characterId);
    }

    @Test
    void calibrationRemovesWrongCharacterReference() throws Exception {
        String token = register();
        long wrongCharacter = createCharacter(token, "误识别角色");
        long rightCharacter = createCharacter(token, "正确角色");
        long assetId = upload(token, "calibrate.png", pngBytes(62, 30));
        insertEmbedding(assetId, "角色图片", "角色图片描述");

        mockMvc.perform(put("/api/characters/assets/" + assetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterId\":" + wrongCharacter + "}"))
                .andExpect(status().isOk());
        assertThat(referenceMapper.selectList(Wrappers.<CharacterReference>lambdaQuery()
                .eq(CharacterReference::getAssetId, assetId))).hasSize(1);

        mockMvc.perform(put("/api/characters/assets/" + assetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterId\":" + rightCharacter + "}"))
                .andExpect(status().isOk());
        assertThat(assetMapper.selectById(assetId).getCharacterId()).isEqualTo(rightCharacter);
        var references = referenceMapper.selectList(Wrappers.<CharacterReference>lambdaQuery()
                .eq(CharacterReference::getAssetId, assetId));
        assertThat(references).hasSize(1);
        assertThat(references.get(0).getCharacterId()).isEqualTo(rightCharacter);

        mockMvc.perform(delete("/api/characters/assets/" + assetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        assertThat(assetMapper.selectById(assetId).getCharacterId()).isNull();
        assertThat(referenceMapper.selectList(Wrappers.<CharacterReference>lambdaQuery()
                .eq(CharacterReference::getAssetId, assetId))).isEmpty();
    }

    private long createCharacter(String token, String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/characters")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return readLong(created, "$.data.id");
    }

    private String register() throws Exception {
        String username = "char_" + UUID.randomUUID().toString().substring(0, 8);
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

    private void insertEmbedding(long assetId, String description, String embedText) {
        AssetEmbedding embedding = new AssetEmbedding();
        embedding.setAssetId(assetId);
        embedding.setDescription(description);
        embedding.setTags(objectMapper.writeValueAsString(List.of("测试")));
        embedding.setEmbedding(objectMapper.writeValueAsString(aiClient.embed(embedText)));
        embedding.setModel("fake");
        embedding.setStatus(AssetEmbedding.STATUS_DONE);
        embeddingMapper.insert(embedding);
        embeddingCache.refresh(assetId);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private long readLong(MvcResult result, String path) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
        return value.longValue();
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(90, 140, 210));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
