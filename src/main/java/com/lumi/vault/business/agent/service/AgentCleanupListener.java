package com.lumi.vault.business.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.entity.AssetFeedback;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.mapper.AssetFeedbackMapper;
import com.lumi.vault.business.asset.event.AssetPurgingEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AgentCleanupListener {

    private final AssetEmbeddingMapper embeddingMapper;
    private final AssetFeedbackMapper feedbackMapper;
    private final AssetEmbeddingCache embeddingCache;

    public AgentCleanupListener(AssetEmbeddingMapper embeddingMapper,
                                AssetFeedbackMapper feedbackMapper,
                                AssetEmbeddingCache embeddingCache) {
        this.embeddingMapper = embeddingMapper;
        this.feedbackMapper = feedbackMapper;
        this.embeddingCache = embeddingCache;
    }

    @EventListener
    public void onAssetPurging(AssetPurgingEvent event) {
        embeddingMapper.deleteById(event.assetId());
        feedbackMapper.delete(Wrappers.<AssetFeedback>lambdaQuery().eq(AssetFeedback::getAssetId, event.assetId()));
        embeddingCache.evict(event.assetId());
    }
}
