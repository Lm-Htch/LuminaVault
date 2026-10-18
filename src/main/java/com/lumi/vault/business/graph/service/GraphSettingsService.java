package com.lumi.vault.business.graph.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.asset.entity.AppSetting;
import com.lumi.vault.business.asset.mapper.AppSettingMapper;
import com.lumi.vault.business.graph.config.GraphProperties;
import com.lumi.vault.business.graph.config.GraphSettings;
import com.lumi.vault.business.graph.dto.GraphSettingsRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * 相似图运行时设置：DB 覆盖值优先于配置文件默认值，改完立即生效（带缓存，更新时刷新）。
 */
@Service
@Slf4j
public class GraphSettingsService {

    public static final String SETTINGS_KEY = "graph.settings";

    private final AppSettingMapper settingMapper;
    private final GraphProperties properties;
    private final ObjectMapper objectMapper;
    private volatile GraphSettings cached;

    public GraphSettingsService(AppSettingMapper settingMapper, GraphProperties properties, ObjectMapper objectMapper) {
        this.settingMapper = settingMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public GraphSettings effective() {
        GraphSettings current = cached;
        if (current == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = load();
                }
                current = cached;
            }
        }
        return current;
    }

    public GraphSettings defaults() {
        return fromProperties();
    }

    public GraphSettings update(GraphSettingsRequest request) {
        GraphSettings merged = request.merge(effective());
        persist(merged);
        cached = merged;
        return merged;
    }

    public GraphSettings reset() {
        settingMapper.deleteById(SETTINGS_KEY);
        cached = fromProperties();
        return cached;
    }

    private void persist(GraphSettings settings) {
        String json = objectMapper.writeValueAsString(settings);
        AppSetting existing = settingMapper.selectById(SETTINGS_KEY);
        if (existing == null) {
            AppSetting row = new AppSetting();
            row.setSettingKey(SETTINGS_KEY);
            row.setSettingValue(json);
            try {
                settingMapper.insert(row);
                return;
            } catch (DuplicateKeyException e) {
                // 并发插入，走更新
            }
        }
        settingMapper.update(null, Wrappers.<AppSetting>lambdaUpdate()
                .eq(AppSetting::getSettingKey, SETTINGS_KEY)
                .set(AppSetting::getSettingValue, json)
                .set(AppSetting::getUpdatedAt, Instant.now()));
    }

    private GraphSettings load() {
        GraphSettings fallback = fromProperties();
        AppSetting row = settingMapper.selectById(SETTINGS_KEY);
        if (row == null || row.getSettingValue() == null || row.getSettingValue().isBlank()) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(row.getSettingValue());
            return new GraphSettings(
                    node.path("enabled").asBoolean(fallback.enabled()),
                    node.path("edgeThreshold").asDouble(fallback.edgeThreshold()),
                    node.path("maxEdgesPerAsset").asInt(fallback.maxEdgesPerAsset()),
                    node.path("fetchPerChannel").asInt(fallback.fetchPerChannel()),
                    node.path("wd14Weight").asDouble(fallback.wd14Weight()),
                    node.path("clipWeight").asDouble(fallback.clipWeight()),
                    node.path("singleChannelDiscount").asDouble(fallback.singleChannelDiscount()),
                    node.path("duplicateThreshold").asDouble(fallback.duplicateThreshold()),
                    node.path("routeWeight").asDouble(fallback.routeWeight()),
                    node.path("routeSeedLimit").asInt(fallback.routeSeedLimit()),
                    node.path("routeNeighbors").asInt(fallback.routeNeighbors()),
                    node.path("routeNearRank").asInt(fallback.routeNearRank()),
                    node.path("routeMidRank").asInt(fallback.routeMidRank()),
                    node.path("routeDecayNear").asDouble(fallback.routeDecayNear()),
                    node.path("routeDecayMid").asDouble(fallback.routeDecayMid()),
                    node.path("routeDecayFar").asDouble(fallback.routeDecayFar()),
                    node.path("feedbackOneHopFactor").asDouble(fallback.feedbackOneHopFactor()),
                    node.path("feedbackTwoHopFactor").asDouble(fallback.feedbackTwoHopFactor()),
                    node.path("feedbackEdgeThreshold").asDouble(fallback.feedbackEdgeThreshold()),
                    node.path("feedbackNeighborLimit").asInt(fallback.feedbackNeighborLimit()),
                    node.path("feedbackSecondHopLimit").asInt(fallback.feedbackSecondHopLimit()));
        } catch (Exception e) {
            log.warn("相似图设置解析失败，回退默认值: {}", e.getMessage());
            return fallback;
        }
    }

    private GraphSettings fromProperties() {
        return new GraphSettings(
                properties.enabled(),
                properties.edgeThreshold(),
                properties.maxEdgesPerAsset(),
                properties.fetchPerChannel(),
                properties.wd14Weight(),
                properties.clipWeight(),
                properties.singleChannelDiscount(),
                properties.duplicateThreshold(),
                properties.routeWeight(),
                properties.routeSeedLimit(),
                properties.routeNeighbors(),
                properties.routeNearRank(),
                properties.routeMidRank(),
                properties.routeDecayNear(),
                properties.routeDecayMid(),
                properties.routeDecayFar(),
                properties.feedbackOneHopFactor(),
                properties.feedbackTwoHopFactor(),
                properties.feedbackEdgeThreshold(),
                properties.feedbackNeighborLimit(),
                properties.feedbackSecondHopLimit());
    }
}
