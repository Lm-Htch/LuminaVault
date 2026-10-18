package com.lumi.vault.business.websearch.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.asset.event.AssetPurgingEvent;
import com.lumi.vault.business.websearch.entity.AssetWebSource;
import com.lumi.vault.business.websearch.mapper.AssetWebSourceMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WebSourceCleanupListener {

    private final AssetWebSourceMapper webSourceMapper;

    public WebSourceCleanupListener(AssetWebSourceMapper webSourceMapper) {
        this.webSourceMapper = webSourceMapper;
    }

    @EventListener
    public void onAssetPurging(AssetPurgingEvent event) {
        webSourceMapper.delete(Wrappers.<AssetWebSource>lambdaQuery()
                .eq(AssetWebSource::getAssetId, event.assetId()));
    }
}
