package com.lumi.vault.business.agent.service;

import lombok.extern.slf4j.Slf4j;


import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.client.AiClient;
import com.lumi.vault.business.agent.client.ImageAnalysis;
import com.lumi.vault.business.agent.config.AiProperties;
import com.lumi.vault.business.agent.dto.AssetAnalysisResponse;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.event.RecommendationInvalidatedEvent;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.asset.storage.AssetStorage;
import com.lumi.vault.business.character.entity.Character;
import com.lumi.vault.business.character.service.CharacterMatchService;
import com.lumi.vault.business.character.service.CharacterService;
import com.lumi.vault.business.folder.service.FolderService;
import com.lumi.vault.business.graph.service.AssetSimilarityGraphService;
import com.lumi.vault.business.model.client.ImageModelClient;
import com.lumi.vault.business.model.dto.ModelAnalysis;
import com.lumi.vault.business.model.dto.ModelTag;
import com.lumi.vault.business.perception.PerceptionAggregator;
import com.lumi.vault.business.perception.PerceptionProfile;
import com.lumi.vault.business.vector.mapper.VectorSearchMapper;
import com.lumi.vault.common.ImageUtils;
import com.lumi.vault.config.VectorProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AssetEnrichmentService {
    private static final int MAX_ERROR_LENGTH = 500;
    private static final double TAG_MATCH_CONFIDENCE = 0.95;
    private static final int ANALYSIS_MAX_SIDE = 2560;

    private final AiClient aiClient;
    private final AiProperties properties;
    private final AssetMapper assetMapper;
    private final AssetEmbeddingMapper embeddingMapper;
    private final AssetStorage assetStorage;
    private final ObjectMapper objectMapper;
    private final CharacterMatchService characterMatchService;
    private final CharacterService characterService;
    private final ImageModelClient imageModelClient;
    private final AssetEmbeddingCache embeddingCache;
    private final ApplicationEventPublisher eventPublisher;
    private final VectorProperties vectorProperties;
    private final VectorSearchMapper vectorSearchMapper;
    private final FolderService folderService;
    private final AssetSimilarityGraphService similarityGraphService;
    private final PerceptionAggregator perceptionAggregator;
    private final ExecutorService clipExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "clip-backfill");
        thread.setDaemon(true);
        return thread;
    });

    public AssetEnrichmentService(AiClient aiClient,
                                  AiProperties properties,
                                  AssetMapper assetMapper,
                                  AssetEmbeddingMapper embeddingMapper,
                                  AssetStorage assetStorage,
                                  ObjectMapper objectMapper,
                                  CharacterMatchService characterMatchService,
                                  CharacterService characterService,
                                  ImageModelClient imageModelClient,
                                  AssetEmbeddingCache embeddingCache,
                                  ApplicationEventPublisher eventPublisher,
                                  VectorProperties vectorProperties,
                                  VectorSearchMapper vectorSearchMapper,
                                  FolderService folderService,
                                  AssetSimilarityGraphService similarityGraphService,
                                  PerceptionAggregator perceptionAggregator) {
        this.aiClient = aiClient;
        this.properties = properties;
        this.assetMapper = assetMapper;
        this.embeddingMapper = embeddingMapper;
        this.assetStorage = assetStorage;
        this.objectMapper = objectMapper;
        this.characterMatchService = characterMatchService;
        this.characterService = characterService;
        this.imageModelClient = imageModelClient;
        this.embeddingCache = embeddingCache;
        this.eventPublisher = eventPublisher;
        this.vectorProperties = vectorProperties;
        this.vectorSearchMapper = vectorSearchMapper;
        this.folderService = folderService;
        this.similarityGraphService = similarityGraphService;
        this.perceptionAggregator = perceptionAggregator;
    }

    @Async
    public void enrichAsync(Long assetId) {
        enrich(assetId);
    }

    public int enrichPending(boolean force) {
        List<Long> ids = assetMapper.selectList(Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getDeleted, false)
                        .orderByAsc(Asset::getId))
                .stream()
                .map(Asset::getId)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        Set<Long> done = embeddingMapper.selectList(Wrappers.<AssetEmbedding>lambdaQuery()
                        .eq(AssetEmbedding::getStatus, AssetEmbedding.STATUS_DONE))
                .stream()
                .map(AssetEmbedding::getAssetId)
                .collect(Collectors.toSet());
        int queued = 0;
        for (Long id : ids) {
            if (!force && done.contains(id)) {
                continue;
            }
            enrichAsync(id);
            queued++;
        }
        return queued;
    }

    public void enrich(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
            return;
        }
        boolean vision = aiClient.available() && aiClient.visionEnabled();
        boolean externalModels = imageModelClient.available();
        if (!vision && !externalModels) {
            upsert(assetId, null, null, null, null, null, null, null, null, null, AssetEmbedding.STATUS_SKIPPED, "AI 与模型服务均未启用");
            return;
        }
        try {
            byte[] image = assetStorage.get(asset.getStorageKey());
            String analysisContentType = ImageUtils.detectContentType(image, asset.getContentType());
            if (needsDownscale(asset)) {
                byte[] downscaled = downscaleForAnalysis(image);
                if (downscaled != null) {
                    image = downscaled;
                    analysisContentType = "image/jpeg";
                }
            }
            String description = null;
            List<String> tagNames = new ArrayList<>();
            PerceptionProfile llmProfile = null;
            if (vision) {
                ImageAnalysis analysis = aiClient.analyzeImage(image, analysisContentType);
                description = analysis.description();
                tagNames.addAll(analysis.tags());
                if (analysis.hasPerception()) {
                    llmProfile = new PerceptionProfile(analysis.mood(), analysis.style(), analysis.tone(),
                            analysis.scene(), analysis.usability(), 0);
                }
            }

            String imageEmbeddingJson = null;
            String imageModel = null;
            String clipEmbeddingJson = null;
            String ocrText = null;
            boolean modelFailed = false;
            String modelError = null;
            List<String> characterTags = List.of();
            if (externalModels) {
                try {
                    ModelAnalysis modelAnalysis = imageModelClient.analyze(image, analysisContentType);
                    for (ModelTag tag : modelAnalysis.tags()) {
                        if (!tagNames.contains(tag.tag())) {
                            tagNames.add(tag.tag());
                        }
                    }
                    characterTags = modelAnalysis.characters();
                    ocrText = modelAnalysis.ocrText();
                    if (modelAnalysis.embedding() != null && !modelAnalysis.embedding().isEmpty()) {
                        imageEmbeddingJson = VectorUtils.toJson(modelAnalysis.embedding(), objectMapper);
                        imageModel = modelAnalysis.model();
                    }
                } catch (Exception e) {
                    modelFailed = true;
                    modelError = e.getMessage();
                    log.warn("资产 {} 外部模型服务处理失败: {}", assetId, e.getMessage());
                }
                try {
                    List<Double> clipVector = imageModelClient.clipEmbed(image, analysisContentType);
                    if (clipVector != null && !clipVector.isEmpty()) {
                        clipEmbeddingJson = VectorUtils.toJson(clipVector, objectMapper);
                    }
                } catch (Exception e) {
                    log.warn("资产 {} CLIP 向量化失败: {}", assetId, e.getMessage());
                }
            }

            String embeddingJson = null;
            String model = null;
            PerceptionProfile profile = perceptionAggregator.merge(llmProfile, perceptionAggregator.aggregate(
                    tagNames, parseColors(asset.getColors()), asset.getWidth(), asset.getHeight()));
            String perceptionJson = perceptionAggregator.serialize(profile);
            if (aiClient.embeddingEnabled()) {
                StringBuilder embeddingTextBuilder = new StringBuilder();
                if (description != null && !description.isBlank()) {
                    embeddingTextBuilder.append(description).append(' ');
                }
                embeddingTextBuilder.append(String.join(" ", tagNames));
                if (ocrText != null && !ocrText.isBlank()) {
                    embeddingTextBuilder.append(' ').append(ocrText);
                }
                String profileText = profile.describeText();
                if (!profileText.isBlank()) {
                    embeddingTextBuilder.append(' ').append(profileText);
                }
                String embeddingText = embeddingTextBuilder.toString();
                if (!embeddingText.isBlank()) {
                    try {
                        embeddingJson = VectorUtils.toJson(aiClient.embed(embeddingText.trim()), objectMapper);
                        model = properties.resolvedEmbeddingModel();
                    } catch (Exception e) {
                        log.warn("资产 {} 向量化失败: {}", assetId, e.getMessage());
                    }
                }
            }

            String finalStatus = AssetEmbedding.STATUS_DONE;
            String finalError = null;
            if (externalModels && modelFailed && imageEmbeddingJson == null && clipEmbeddingJson == null) {
                finalStatus = AssetEmbedding.STATUS_FAILED;
                finalError = truncate("外部模型服务处理失败: " + modelError);
            }
            upsert(assetId,
                    description,
                    VectorUtils.toJson(tagNames, objectMapper),
                    VectorUtils.toJson(characterTags, objectMapper),
                    ocrText,
                    perceptionJson,
                    embeddingJson,
                    model,
                    imageEmbeddingJson,
                    imageModel,
                    finalStatus,
                    finalError);
            if (AssetEmbedding.STATUS_DONE.equals(finalStatus)) {
                matchCharacter(asset, assetId, characterTags.isEmpty() ? tagNames : characterTags);
                syncImageVector(assetId, imageEmbeddingJson, imageModel);
                syncClipEmbedding(assetId, clipEmbeddingJson);
                try {
                    similarityGraphService.rebuildEdges(assetId);
                } catch (Exception e) {
                    log.warn("资产 {} 相似图边重建失败: {}", assetId, e.getMessage());
                }
                try {
                    folderService.classifyIfUnassigned(asset, description, tagNames);
                } catch (Exception e) {
                    log.warn("资产 {} 自动分类失败: {}", assetId, e.getMessage());
                }
                eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
            }
        } catch (Exception e) {
            log.warn("资产 {} AI 分析失败: {}", assetId, e.getMessage());
            upsert(assetId, null, null, null, null, null, null, null, null, null, AssetEmbedding.STATUS_FAILED, truncate(e.getMessage()));
        }
    }

    private boolean needsDownscale(Asset asset) {
        if (asset.getSizeBytes() != null && asset.getSizeBytes() > properties.maxImageBytes()) {
            return true;
        }
        return asset.getWidth() != null && asset.getHeight() != null
                && Math.max(asset.getWidth(), asset.getHeight()) > ANALYSIS_MAX_SIDE;
    }

    private byte[] downscaleForAnalysis(byte[] image) {
        return ImageUtils.downscaleJpeg(image, ANALYSIS_MAX_SIDE);
    }

    public AssetAnalysisResponse analysis(Long assetId) {
        AssetEmbedding embedding = embeddingMapper.selectById(assetId);
        if (embedding == null) {
            return new AssetAnalysisResponse(assetId, null, List.of(), "NONE", null, null, null);
        }
        return new AssetAnalysisResponse(
                assetId,
                embedding.getDescription(),
                VectorUtils.stringsFromJson(embedding.getTags(), objectMapper),
                embedding.getStatus(),
                embedding.getModel(),
                embedding.getError(),
                AssetAnalysisResponse.Perception.from(perceptionAggregator.parse(embedding.getPerception())));
    }

    private void matchCharacter(Asset asset, Long assetId, List<String> tagNames) {
        if (asset.getCharacterId() != null) {
            return;
        }
        Character byTag = characterService.findOrCreateByTags(tagNames);
        if (byTag != null) {
            setAssetCharacter(assetId, byTag.getId(), TAG_MATCH_CONFIDENCE);
            return;
        }
        CharacterMatchService.MatchResult match = characterMatchService.match(assetId);
        if (match != null) {
            setAssetCharacter(assetId, match.characterId(), match.confidence());
        }
    }

    private void syncImageVector(Long assetId, String imageEmbeddingJson, String imageModel) {
        if (!vectorProperties.enabled() || imageEmbeddingJson == null
                || !vectorProperties.imageModel().equals(imageModel)) {
            return;
        }
        try {
            vectorSearchMapper.updateImageVector(assetId, imageEmbeddingJson);
        } catch (Exception e) {
            log.warn("同步图像向量失败: {}", e.getMessage());
        }
    }

    private void syncClipEmbedding(Long assetId, String clipEmbeddingJson) {
        if (clipEmbeddingJson == null) {
            return;
        }
        try {
            embeddingMapper.update(null, Wrappers.<AssetEmbedding>lambdaUpdate()
                    .eq(AssetEmbedding::getAssetId, assetId)
                    .set(AssetEmbedding::getClipEmbedding, clipEmbeddingJson));
            if (vectorProperties.enabled()) {
                vectorSearchMapper.updateClipVector(assetId, clipEmbeddingJson);
            }
            embeddingCache.refresh(assetId);
        } catch (Exception e) {
            log.warn("CLIP 向量存储失败: {}", e.getMessage());
        }
    }

    /**
     * 为已分析但缺少 CLIP 向量的资产补算（后台单线程顺序执行），返回入队数量。
     */
    public int clipPending() {
        if (!imageModelClient.available()) {
            return 0;
        }
        List<Long> ids = embeddingMapper.selectList(Wrappers.<AssetEmbedding>lambdaQuery()
                        .eq(AssetEmbedding::getStatus, AssetEmbedding.STATUS_DONE)
                        .isNull(AssetEmbedding::getClipEmbedding))
                .stream()
                .map(AssetEmbedding::getAssetId)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        clipExecutor.submit(() -> {
            for (Long id : ids) {
                try {
                    backfillClip(id);
                } catch (Exception e) {
                    log.debug("CLIP 回填失败({}): {}", id, e.getMessage());
                }
            }
        });
        return ids.size();
    }

    private void backfillClip(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
            return;
        }
        byte[] image = assetStorage.get(asset.getStorageKey());
        List<Double> vector = imageModelClient.clipEmbed(image, asset.getContentType());
        if (vector == null || vector.isEmpty()) {
            return;
        }
        syncClipEmbedding(assetId, VectorUtils.toJson(vector, objectMapper));
        try {
            similarityGraphService.rebuildEdges(assetId);
        } catch (Exception e) {
            log.warn("资产 {} 相似图边重建失败: {}", assetId, e.getMessage());
        }
    }

    private void setAssetCharacter(Long assetId, Long characterId, double confidence) {
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, assetId)
                .set(Asset::getCharacterId, characterId)
                .set(Asset::getCharacterConfidence, confidence));
    }

    private void upsert(Long assetId, String description, String tags, String characterTags, String ocrText,
                        String perception, String embeddingJson, String model, String imageEmbeddingJson,
                        String imageModel, String status, String error) {
        AssetEmbedding existing = embeddingMapper.selectById(assetId);
        if (existing == null) {
            AssetEmbedding row = new AssetEmbedding();
            row.setAssetId(assetId);
            row.setDescription(description);
            row.setTags(tags);
            row.setCharacterTags(characterTags);
            row.setOcrText(ocrText);
            row.setPerception(perception);
            row.setEmbedding(embeddingJson);
            row.setModel(model);
            row.setImageEmbedding(imageEmbeddingJson);
            row.setImageModel(imageModel);
            row.setStatus(status);
            row.setError(error);
            embeddingMapper.insert(row);
            embeddingCache.refresh(assetId);
            return;
        }
        embeddingMapper.update(null, Wrappers.<AssetEmbedding>lambdaUpdate()
                .eq(AssetEmbedding::getAssetId, assetId)
                .set(AssetEmbedding::getDescription, description)
                .set(AssetEmbedding::getTags, tags)
                .set(AssetEmbedding::getCharacterTags, characterTags)
                .set(AssetEmbedding::getOcrText, ocrText)
                .set(AssetEmbedding::getPerception, perception)
                .set(AssetEmbedding::getEmbedding, embeddingJson)
                .set(AssetEmbedding::getModel, model)
                .set(AssetEmbedding::getImageEmbedding, imageEmbeddingJson)
                .set(AssetEmbedding::getImageModel, imageModel)
                .set(AssetEmbedding::getStatus, status)
                .set(AssetEmbedding::getError, error)
                .set(AssetEmbedding::getUpdatedAt, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        embeddingCache.refresh(assetId);
    }

    /**
     * 为已分析资产回填感知画像（规则聚合 + 保留既有 LLM 特征），返回更新数量。
     */
    public int perceptionPending() {
        List<AssetEmbedding> rows = embeddingMapper.selectList(Wrappers.<AssetEmbedding>lambdaQuery()
                .eq(AssetEmbedding::getStatus, AssetEmbedding.STATUS_DONE));
        int updated = 0;
        for (AssetEmbedding row : rows) {
            try {
                Asset asset = assetMapper.selectById(row.getAssetId());
                if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
                    continue;
                }
                PerceptionProfile rule = perceptionAggregator.aggregate(
                        VectorUtils.stringsFromJson(row.getTags(), objectMapper),
                        parseColors(asset.getColors()), asset.getWidth(), asset.getHeight());
                PerceptionProfile merged = perceptionAggregator.merge(
                        perceptionAggregator.parse(row.getPerception()), rule);
                String json = perceptionAggregator.serialize(merged);
                if (json == null || json.equals(row.getPerception())) {
                    continue;
                }
                embeddingMapper.update(null, Wrappers.<AssetEmbedding>lambdaUpdate()
                        .eq(AssetEmbedding::getAssetId, row.getAssetId())
                        .set(AssetEmbedding::getPerception, json)
                        .set(AssetEmbedding::getUpdatedAt, Instant.now().truncatedTo(ChronoUnit.MICROS)));
                embeddingCache.refresh(row.getAssetId());
                updated++;
            } catch (Exception e) {
                log.warn("资产 {} 特征聚合回填失败: {}", row.getAssetId(), e.getMessage());
            }
        }
        return updated;
    }

    private List<String> parseColors(String colors) {
        if (colors == null || colors.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : colors.split("[,，]")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
