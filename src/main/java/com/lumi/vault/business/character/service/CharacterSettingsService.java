package com.lumi.vault.business.character.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.asset.entity.AppSetting;
import com.lumi.vault.business.asset.mapper.AppSettingMapper;
import com.lumi.vault.business.character.config.CharacterProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class CharacterSettingsService {

    public static final String AUTO_CREATE_KEY = "character.auto-create";

    private final AppSettingMapper appSettingMapper;
    private final CharacterProperties properties;

    public CharacterSettingsService(AppSettingMapper appSettingMapper, CharacterProperties properties) {
        this.appSettingMapper = appSettingMapper;
        this.properties = properties;
    }

    public boolean autoCreate() {
        AppSetting setting = appSettingMapper.selectById(AUTO_CREATE_KEY);
        if (setting == null || setting.getSettingValue() == null || setting.getSettingValue().isBlank()) {
            return properties.autoCreate();
        }
        return Boolean.parseBoolean(setting.getSettingValue());
    }

    public boolean updateAutoCreate(boolean value) {
        AppSetting setting = appSettingMapper.selectById(AUTO_CREATE_KEY);
        Instant now = Instant.now();
        if (setting == null) {
            setting = new AppSetting();
            setting.setSettingKey(AUTO_CREATE_KEY);
            setting.setSettingValue(Boolean.toString(value));
            try {
                appSettingMapper.insert(setting);
            } catch (DuplicateKeyException e) {
                updateValue(value, now);
            }
        } else {
            updateValue(value, now);
        }
        return value;
    }

    private void updateValue(boolean value, Instant now) {
        appSettingMapper.update(null, Wrappers.<AppSetting>lambdaUpdate()
                .eq(AppSetting::getSettingKey, AUTO_CREATE_KEY)
                .set(AppSetting::getSettingValue, Boolean.toString(value))
                .set(AppSetting::getUpdatedAt, now));
    }
}
