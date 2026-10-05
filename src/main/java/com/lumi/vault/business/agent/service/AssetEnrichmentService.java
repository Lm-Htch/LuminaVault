package com.lumi.vault.business.agent.service;

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
import com.lumi.vault.business.model.client.ImageModelClient;
import com.lumi.vault.business.model.dto.ModelAnalysis;
import com.lumi.vault.business.model.dto.ModelTag;
import com.lumi.vault.business.vector.mapper.VectorSearchMapper;
import com.lumi.vault.config.VectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AssetEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(AssetEnrichmentService.class);
    private static final int MAX_ERROR_LENGTH = 500;
    private static final double TAG_MATCH_CONFIDENCE = 0.95;

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
                                  FolderService folderService) {
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
            upsert(assetId, null, null, null, null, null, null, null, AssetEmbedding.STATUS_SKIPPED, "AI 与模型服务均未启用");
            return;
        }
        if (asset.getSizeBytes() != null && asset.getSizeBytes() > properties.maxImageBytes()) {
            upsert(assetId, null, null, null, null, null, null, null, AssetEmbedding.STATUS_SKIPPED, "图片超过分析大小限制");
            return;
        }
        try {
            byte[] image = assetStorage.get(asset.getStorageKey());
            String description = null;
            List<String> tagNames = new ArrayList<>();
            if (vision) {
                ImageAnalysis analysis = aiClient.analyzeImage(image, asset.getContentType());
                description = analysis.description();
                tagNames.addAll(analysis.tags());
            }

            String imageEmbeddingJson = null;
            String imageModel = null;
            List<String> characterTags = List.of();
            if (externalModels) {
                try {
                    ModelAnalysis modelAnalysis = imageModelClient.analyze(image, asset.getContentType());
                    for (ModelTag tag : modelAnalysis.tags()) {
                        if (!tagNames.contains(tag.tag())) {
                            tagNames.add(tag.tag());
                        }
                    }
                    characterTags = modelAnalysis.characters();
                    if (modelAnalysis.embedding() != null && !modelAnalysis.embedding().isEmpty()) {
                        imageEmbeddingJson = VectorUtils.toJson(modelAnalysis.embedding(), objectMapper);
                        imageModel = modelAnalysis.model();
                    }
                } catch (Exception e) {
                    log.warn("资产 {} 外部模型服务处理失败: {}", assetId, e.getMessage());
                }
            }

            String embeddingJson = null;
            String model = null;
            if (aiClient.embeddingEnabled()) {
                String embeddingText = (description == null || description.isBlank() ? "" : description + " ")
                        + String.join(" ", tagNames);
                if (!embeddingText.isBlank()) {
                    try {
                        embeddingJson = VectorUtils.toJson(aiClient.embed(embeddingText.trim()), objectMapper);
                        model = properties.resolvedEmbeddingModel();
                    } catch (Exception e) {
                        log.warn("资产 {} 向量化失败: {}", assetId, e.getMessage());
                    }
                }
            }

            upsert(assetId,
                    description,
                    VectorUtils.toJson(tagNames, objectMapper),
                    VectorUtils.toJson(characterTags, objectMapper),
                    embeddingJson,
                    model,
                    imageEmbeddingJson,
                    imageModel,
                    AssetEmbedding.STATUS_DONE,
                    null);
            matchCharacter(asset, assetId, characterTags.isEmpty() ? tagNames : characterTags);
            syncImageVector(assetId, imageEmbeddingJson, imageModel);
            try {
                folderService.classifyIfUnassigned(asset, description, tagNames);
            } catch (Exception e) {
                log.warn("资产 {} 自动分类失败: {}", assetId, e.getMessage());
            }
            eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
        } catch (Exception e) {
            log.warn("资产 {} AI 分析失败: {}", assetId, e.getMessage());
            upsert(assetId, null, null, null, null, null, null, null, AssetEmbedding.STATUS_FAILED, truncate(e.getMessage()));
        }
    }

    public AssetAnalysisResponse analysis(Long assetId) {
        AssetEmbedding embedding = embeddingMapper.selectById(assetId);
        if (embedding == null) {
            return new AssetAnalysisResponse(assetId, null, List.of(), "NONE", null, null);
        }
        return new AssetAnalysisResponse(
                assetId,
                embedding.getDescription(),
                VectorUtils.stringsFromJson(embedding.getTags(), objectMapper),
                embedding.getStatus(),
                embedding.getModel(),
                embedding.getError());
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
            log.warn("写入图像向量失败: {}", e.getMessage());
        }
    }

    private void setAssetCharacter(Long assetId, Long characterId, double confidence) {
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, assetId)
                .set(Asset::getCharacterId, characterId)
                .set(Asset::getCharacterConfidence, confidence));
    }

    private void upsert(Long assetId, String description, String tags, String characterTags, String embeddingJson,
                        String model, String imageEmbeddingJson, String imageModel, String status, String error) {
        AssetEmbedding existing = embeddingMapper.selectById(assetId);
        if (existing == null) {
            AssetEmbedding row = new AssetEmbedding();
            row.setAssetId(assetId);
            row.setDescription(description);
            row.setTags(tags);
            row.setCharacterTags(characterTags);
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
                .set(AssetEmbedding::getEmbedding, embeddingJson)
                .set(AssetEmbedding::getModel, model)
                .set(AssetEmbedding::getImageEmbedding, imageEmbeddingJson)
                .set(AssetEmbedding::getImageModel, imageModel)
                .set(AssetEmbedding::getStatus, status)
                .set(AssetEmbedding::getError, error)
                .set(AssetEmbedding::getUpdatedAt, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        embeddingCache.refresh(assetId);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
