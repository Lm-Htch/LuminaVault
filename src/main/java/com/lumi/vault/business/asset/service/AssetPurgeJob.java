package com.lumi.vault.business.asset.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AssetPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AssetPurgeJob.class);

    private final AssetService assetService;

    public AssetPurgeJob(AssetService assetService) {
        this.assetService = assetService;
    }

    @Scheduled(cron = "${lumina.asset.purge-cron:0 0 4 * * *}")
    public void purgeExpiredAssets() {
        int purged = assetService.purgeExpired();
        if (purged > 0) {
            log.info("回收站清理完成，永久删除 {} 个资产", purged);
        }
    }
}
