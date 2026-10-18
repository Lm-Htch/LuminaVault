package com.lumi.vault.business.agent.service;

import lombok.extern.slf4j.Slf4j;


import com.lumi.vault.business.asset.event.AssetUploadedEvent;
import com.lumi.vault.business.websearch.service.WebSourceService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class AssetEnrichmentTrigger {

    private final AssetEnrichmentService enrichmentService;
    private final WebSourceService webSourceService;

    public AssetEnrichmentTrigger(AssetEnrichmentService enrichmentService, WebSourceService webSourceService) {
        this.enrichmentService = enrichmentService;
        this.webSourceService = webSourceService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssetUploaded(AssetUploadedEvent event) {
        log.debug("提交资产 {} 的异步 AI 标注", event.assetId());
        enrichmentService.enrichAsync(event.assetId());
        webSourceService.lookupAsync(event.assetId());
    }
}
