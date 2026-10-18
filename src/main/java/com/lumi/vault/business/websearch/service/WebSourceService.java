package com.lumi.vault.business.websearch.service;

import lombok.extern.slf4j.Slf4j;


import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.event.RecommendationInvalidatedEvent;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import com.lumi.vault.business.asset.entity.AppSetting;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AppSettingMapper;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.asset.storage.AssetStorage;
import com.lumi.vault.business.character.entity.Character;
import com.lumi.vault.business.character.service.CharacterService;
import com.lumi.vault.business.websearch.client.WebSourceException;
import com.lumi.vault.business.websearch.client.WebSourceLookupClient;
import com.lumi.vault.business.websearch.config.WebSearchProperties;
import com.lumi.vault.business.websearch.dto.WebSourceResponse;
import com.lumi.vault.business.websearch.dto.WebSourceResult;
import com.lumi.vault.business.websearch.entity.AssetWebSource;
import com.lumi.vault.business.websearch.mapper.AssetWebSourceMapper;
import com.lumi.vault.common.ImageUtils;
import com.lumi.vault.exception.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图片来源反查（SauceNAO）：限流、日配额、结果落库与利用（作者/角色标签）。
 */
@Service
@Slf4j
public class WebSourceService {

    public static final String STATUS_FOUND = "FOUND";
    public static final String STATUS_NO_MATCH = "NO_MATCH";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_QUOTA = "QUOTA";

    private static final String QUOTA_EXCEEDED_DATE_KEY = "web-search.quota-exceeded-date";
    private static final String QUOTA_USAGE_PREFIX = "web-search.quota.";
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_IMAGE_SIDE = 2560;
    private static final int MAX_BATCH = 200;

    private final WebSearchProperties properties;
    private final WebSourceLookupClient lookupClient;
    private final AssetWebSourceMapper webSourceMapper;
    private final AssetMapper assetMapper;
    private final AssetEmbeddingMapper embeddingMapper;
    private final AssetEmbeddingCache embeddingCache;
    private final AppSettingMapper appSettingMapper;
    private final CharacterService characterService;
    private final AssetStorage assetStorage;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "web-source-lookup");
        thread.setDaemon(true);
        return thread;
    });
    private final Object throttleLock = new Object();
    private long lastRequestAt = 0;

    public WebSourceService(WebSearchProperties properties,
                            WebSourceLookupClient lookupClient,
                            AssetWebSourceMapper webSourceMapper,
                            AssetMapper assetMapper,
                            AssetEmbeddingMapper embeddingMapper,
                            AssetEmbeddingCache embeddingCache,
                            AppSettingMapper appSettingMapper,
                            CharacterService characterService,
                            AssetStorage assetStorage,
                            ObjectMapper objectMapper,
                            ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.lookupClient = lookupClient;
        this.webSourceMapper = webSourceMapper;
        this.assetMapper = assetMapper;
        this.embeddingMapper = embeddingMapper;
        this.embeddingCache = embeddingCache;
        this.appSettingMapper = appSettingMapper;
        this.characterService = characterService;
        this.assetStorage = assetStorage;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    public boolean available() {
        return properties.available() && lookupClient.available();
    }

    public WebSourceResponse source(long assetId) {
        AssetWebSource row = find(assetId);
        return row == null ? null : toResponse(row);
    }

    public void lookupAsync(long assetId) {
        executor.submit(() -> {
            try {
                lookup(assetId, false);
            } catch (Exception e) {
                log.debug("自动来源反查失败({}): {}", assetId, e.getMessage());
            }
        });
    }

    public int enqueue(List<Long> assetIds) {
        List<Long> ids = assetIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_BATCH)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        executor.submit(() -> {
            for (Long id : ids) {
                try {
                    lookup(id, false);
                } catch (Exception e) {
                    log.debug("批量来源反查失败({}): {}", id, e.getMessage());
                }
            }
        });
        return ids.size();
    }

    public WebSourceResponse lookup(long assetId, boolean manual) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
            if (manual) {
                throw ApiException.notFound("资产不存在");
            }
            return null;
        }
        if (!available()) {
            if (manual) {
                throw ApiException.badRequest("来源反查未启用（需配置 " + properties.provider() + " 的 key）");
            }
            return null;
        }
        if (quotaExceededToday()) {
            if (manual) {
                throw ApiException.badRequest("今日反查配额已用尽，已自动暂停，明天恢复");
            }
            return null;
        }
        if (!manual) {
            AssetWebSource existing = find(assetId);
            if (existing != null) {
                return toResponse(existing);
            }
        }
        incrementUsage();
        throttle();
        try {
            PreparedImage prepared = prepare(asset);
            WebSourceResult result = lookupClient.lookup(prepared.bytes(), prepared.contentType());
            AssetWebSource saved = result == null
                    ? persist(asset, STATUS_NO_MATCH, null, null)
                    : persist(asset, STATUS_FOUND, result, null);
            if (result != null) {
                applyEffects(asset, result);
            }
            return toResponse(saved);
        } catch (WebSourceException e) {
            if (e.quota()) {
                markQuotaExceeded();
            }
            persist(asset, e.quota() ? STATUS_QUOTA : STATUS_FAILED, null, e.getMessage());
            if (manual) {
                throw ApiException.badRequest(e.getMessage());
            }
            log.debug("来源反查失败({}): {}", assetId, e.getMessage());
            return null;
        } catch (Exception e) {
            persist(asset, STATUS_FAILED, null, e.getMessage());
            if (manual) {
                throw ApiException.badRequest("来源反查失败: " + e.getMessage());
            }
            return null;
        }
    }

    private void applyEffects(Asset asset, WebSourceResult result) {
        if (result.author() != null && (asset.getAuthor() == null || asset.getAuthor().isBlank())) {
            assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                    .eq(Asset::getId, asset.getId())
                    .set(Asset::getAuthor, result.author())
                    .set(Asset::getUpdatedAt, Instant.now()));
        }
        List<String> characters = result.characters();
        if (characters.isEmpty()) {
            return;
        }
        AssetEmbedding embedding = embeddingMapper.selectById(asset.getId());
        if (embedding != null) {
            List<String> tags = new ArrayList<>(VectorUtils.stringsFromJson(embedding.getTags(), objectMapper));
            boolean changed = false;
            for (String character : characters) {
                if (!tags.contains(character)) {
                    tags.add(character);
                    changed = true;
                }
            }
            if (changed) {
                embeddingMapper.update(null, Wrappers.<AssetEmbedding>lambdaUpdate()
                        .eq(AssetEmbedding::getAssetId, asset.getId())
                        .set(AssetEmbedding::getTags, VectorUtils.toJson(tags, objectMapper)));
                embeddingCache.refresh(asset.getId());
                eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
            }
        }
        if (asset.getCharacterId() == null) {
            Character matched = characterService.matchByTags(characters);
            if (matched != null) {
                assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                        .eq(Asset::getId, asset.getId())
                        .set(Asset::getCharacterId, matched.getId())
                        .set(Asset::getCharacterConfidence, result.similarity())
                        .set(Asset::getUpdatedAt, Instant.now()));
            }
        }
    }

    private AssetWebSource persist(Asset asset, String status, WebSourceResult result, String failureMessage) {
        AssetWebSource existing = find(asset.getId());
        Instant now = Instant.now();
        String characters = result == null ? null : VectorUtils.toJson(result.characters(), objectMapper);
        String raw = result != null ? result.raw() : failureMessage;
        if (existing == null) {
            AssetWebSource row = new AssetWebSource();
            row.setAssetId(asset.getId());
            row.setProvider(properties.provider());
            row.setStatus(status);
            row.setSimilarity(result == null ? null : result.similarity());
            row.setSourceType(result == null ? null : result.sourceType());
            row.setSourceUrl(result == null ? null : result.sourceUrl());
            row.setSourceId(result == null ? null : result.sourceId());
            row.setTitle(result == null ? null : result.title());
            row.setAuthor(result == null ? null : result.author());
            row.setCharacters(characters);
            row.setRaw(raw);
            webSourceMapper.insert(row);
            return row;
        }
        webSourceMapper.update(null, Wrappers.<AssetWebSource>lambdaUpdate()
                .eq(AssetWebSource::getId, existing.getId())
                .set(AssetWebSource::getStatus, status)
                .set(AssetWebSource::getSimilarity, result == null ? null : result.similarity())
                .set(AssetWebSource::getSourceType, result == null ? null : result.sourceType())
                .set(AssetWebSource::getSourceUrl, result == null ? null : result.sourceUrl())
                .set(AssetWebSource::getSourceId, result == null ? null : result.sourceId())
                .set(AssetWebSource::getTitle, result == null ? null : result.title())
                .set(AssetWebSource::getAuthor, result == null ? null : result.author())
                .set(AssetWebSource::getCharacters, characters)
                .set(AssetWebSource::getRaw, raw)
                .set(AssetWebSource::getUpdatedAt, now));
        return webSourceMapper.selectById(existing.getId());
    }

    private AssetWebSource find(long assetId) {
        return webSourceMapper.selectOne(Wrappers.<AssetWebSource>lambdaQuery()
                .eq(AssetWebSource::getAssetId, assetId)
                .eq(AssetWebSource::getProvider, properties.provider())
                .last("limit 1"));
    }

    private record PreparedImage(byte[] bytes, String contentType) {
    }

    private PreparedImage prepare(Asset asset) {
        byte[] image = assetStorage.get(asset.getStorageKey());
        String contentType = asset.getContentType();
        boolean oversized = image.length > MAX_IMAGE_BYTES
                || (asset.getWidth() != null && asset.getHeight() != null
                && Math.max(asset.getWidth(), asset.getHeight()) > MAX_IMAGE_SIDE);
        if (oversized) {
            byte[] downscaled = ImageUtils.downscaleJpeg(image, MAX_IMAGE_SIDE);
            if (downscaled != null) {
                return new PreparedImage(downscaled, "image/jpeg");
            }
        }
        return new PreparedImage(image, contentType == null ? "image/jpeg" : contentType);
    }

    private void throttle() {
        long delay = properties.requestDelayMs();
        if (delay <= 0) {
            return;
        }
        synchronized (throttleLock) {
            long since = System.currentTimeMillis() - lastRequestAt;
            if (since < delay) {
                try {
                    Thread.sleep(delay - since);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    private boolean quotaExceededToday() {
        AppSetting setting = appSettingMapper.selectById(QUOTA_EXCEEDED_DATE_KEY);
        return setting != null && LocalDate.now().toString().equals(setting.getSettingValue());
    }

    private void markQuotaExceeded() {
        upsertSetting(QUOTA_EXCEEDED_DATE_KEY, LocalDate.now().toString());
    }

    private void incrementUsage() {
        String key = QUOTA_USAGE_PREFIX + properties.provider() + "." + LocalDate.now();
        int used = 0;
        AppSetting setting = appSettingMapper.selectById(key);
        if (setting != null && setting.getSettingValue() != null) {
            try {
                used = Integer.parseInt(setting.getSettingValue().trim());
            } catch (NumberFormatException ignored) {
                used = 0;
            }
        }
        if (properties.dailyQuota() > 0 && used >= properties.dailyQuota()) {
            markQuotaExceeded();
        }
        upsertSetting(key, Integer.toString(used + 1));
    }

    private void upsertSetting(String key, String value) {
        AppSetting setting = appSettingMapper.selectById(key);
        if (setting == null) {
            setting = new AppSetting();
            setting.setSettingKey(key);
            setting.setSettingValue(value);
            appSettingMapper.insert(setting);
            return;
        }
        appSettingMapper.update(null, Wrappers.<AppSetting>lambdaUpdate()
                .eq(AppSetting::getSettingKey, key)
                .set(AppSetting::getSettingValue, value)
                .set(AppSetting::getUpdatedAt, Instant.now()));
    }

    private WebSourceResponse toResponse(AssetWebSource row) {
        return new WebSourceResponse(row.getProvider(), row.getStatus(), row.getSimilarity(), row.getSourceType(),
                row.getSourceUrl(), row.getSourceId(), row.getTitle(), row.getAuthor(),
                VectorUtils.stringsFromJson(row.getCharacters(), objectMapper));
    }
}
