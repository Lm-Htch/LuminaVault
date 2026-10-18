package com.lumi.vault.business.asset.service;

import lombok.extern.slf4j.Slf4j;


import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AssetPurgeJob {

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
