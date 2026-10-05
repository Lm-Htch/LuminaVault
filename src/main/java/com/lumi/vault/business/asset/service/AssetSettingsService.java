package com.lumi.vault.business.asset.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.asset.config.AssetProperties;
import com.lumi.vault.business.asset.entity.AppSetting;
import com.lumi.vault.business.asset.mapper.AppSettingMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AssetSettingsService {

    public static final String TRASH_RETENTION_DAYS_KEY = "asset.trash-retention-days";

    private final AppSettingMapper appSettingMapper;
    private final AssetProperties properties;

    public AssetSettingsService(AppSettingMapper appSettingMapper, AssetProperties properties) {
        this.appSettingMapper = appSettingMapper;
        this.properties = properties;
    }

    public int retentionDays() {
        AppSetting setting = appSettingMapper.selectById(TRASH_RETENTION_DAYS_KEY);
        if (setting == null) {
            return properties.trashRetentionDays();
        }
        try {
            return Integer.parseInt(setting.getSettingValue());
        } catch (NumberFormatException e) {
            return properties.trashRetentionDays();
        }
    }

    public int updateRetentionDays(int days) {
        AppSetting setting = appSettingMapper.selectById(TRASH_RETENTION_DAYS_KEY);
        Instant now = Instant.now();
        if (setting == null) {
            setting = new AppSetting();
            setting.setSettingKey(TRASH_RETENTION_DAYS_KEY);
            setting.setSettingValue(Integer.toString(days));
            try {
                appSettingMapper.insert(setting);
            } catch (DuplicateKeyException e) {
                appSettingMapper.update(null, Wrappers.<AppSetting>lambdaUpdate()
                        .eq(AppSetting::getSettingKey, TRASH_RETENTION_DAYS_KEY)
                        .set(AppSetting::getSettingValue, Integer.toString(days))
                        .set(AppSetting::getUpdatedAt, now));
            }
        } else {
            appSettingMapper.update(null, Wrappers.<AppSetting>lambdaUpdate()
                    .eq(AppSetting::getSettingKey, TRASH_RETENTION_DAYS_KEY)
                    .set(AppSetting::getSettingValue, Integer.toString(days))
                    .set(AppSetting::getUpdatedAt, now));
        }
        return days;
    }
}
