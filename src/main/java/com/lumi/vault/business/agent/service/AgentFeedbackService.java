package com.lumi.vault.business.agent.service;

import lombok.extern.slf4j.Slf4j;


import com.lumi.vault.business.agent.client.AiClient;
import com.lumi.vault.business.agent.config.AiProperties;
import com.lumi.vault.business.agent.dto.AgentFeedbackRequest;
import com.lumi.vault.business.agent.entity.AssetFeedback;
import com.lumi.vault.business.agent.event.RecommendationInvalidatedEvent;
import com.lumi.vault.business.agent.mapper.AssetFeedbackMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.character.service.CharacterService;
import com.lumi.vault.exception.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class AgentFeedbackService {

    private final AssetMapper assetMapper;
    private final AssetFeedbackMapper feedbackMapper;
    private final AiClient aiClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final CharacterService characterService;
    private final ApplicationEventPublisher eventPublisher;

    public AgentFeedbackService(AssetMapper assetMapper,
                                AssetFeedbackMapper feedbackMapper,
                                AiClient aiClient,
                                AiProperties aiProperties,
                                ObjectMapper objectMapper,
                                CharacterService characterService,
                                ApplicationEventPublisher eventPublisher) {
        this.assetMapper = assetMapper;
        this.feedbackMapper = feedbackMapper;
        this.aiClient = aiClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.characterService = characterService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void record(long userId, AgentFeedbackRequest request) {
        Asset asset = assetMapper.selectById(request.assetId());
        if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
            throw ApiException.notFound("资产不存在");
        }
        String text = request.description() != null && !request.description().isBlank()
                ? request.description() : request.query();
        String embeddingJson = null;
        if (aiClient.embeddingEnabled() && text != null && !text.isBlank()) {
            try {
                embeddingJson = VectorUtils.toJson(aiClient.embed(text), objectMapper);
            } catch (Exception e) {
                log.warn("反馈向量化失败: {}", e.getMessage());
            }
        }
        AssetFeedback feedback = new AssetFeedback();
        feedback.setUserId(userId);
        feedback.setAssetId(asset.getId());
        feedback.setQueryText(request.query());
        feedback.setAction(request.action());
        feedback.setWeight(request.weight() == null ? 1.0 : request.weight());
        feedback.setEmbedding(embeddingJson);
        feedbackMapper.insert(feedback);
        if (AssetFeedback.ACTION_SELECT.equals(request.action()) && embeddingJson != null) {
            characterService.accumulateReference(asset, request.query(), embeddingJson,
                    aiProperties.resolvedEmbeddingModel());
        }
        eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
    }
}
