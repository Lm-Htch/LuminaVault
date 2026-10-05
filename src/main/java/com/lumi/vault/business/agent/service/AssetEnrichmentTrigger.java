package com.lumi.vault.business.agent.service;

import com.lumi.vault.business.asset.event.AssetUploadedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AssetEnrichmentTrigger {

    private static final Logger log = LoggerFactory.getLogger(AssetEnrichmentTrigger.class);

    private final AssetEnrichmentService enrichmentService;

    public AssetEnrichmentTrigger(AssetEnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssetUploaded(AssetUploadedEvent event) {
        log.debug("提交资产 {} 的异步 AI 标注", event.assetId());
        enrichmentService.enrichAsync(event.assetId());
    }
}
