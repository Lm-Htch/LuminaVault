package com.lumi.vault.business.graph.service;

import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.graph.config.GraphSettings;
import com.lumi.vault.business.graph.mapper.AssetSimilarityEdgeMapper;
import com.lumi.vault.business.vector.dto.ImageVectorMatch;
import com.lumi.vault.business.vector.mapper.VectorSearchMapper;
import com.lumi.vault.config.VectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 资产相似度无向图：节点=资产，边=加权相似度（对称存一条，a_id &lt; b_id）。
 * 边由 WD14 图像向量与 CLIP 向量双通道近邻融合生成，参数全部来自 {@link GraphSettings}（配置/前端可调）。
 */
@Service
@Slf4j
public class AssetSimilarityGraphService {

    private final AssetEmbeddingMapper embeddingMapper;
    private final AssetEmbeddingCache embeddingCache;
    private final VectorSearchMapper vectorSearchMapper;
    private final VectorProperties vectorProperties;
    private final AssetMapper assetMapper;
    private final AssetSimilarityEdgeMapper edgeMapper;
    private final ObjectMapper objectMapper;
    private final GraphSettingsService settingsService;

    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    public AssetSimilarityGraphService(AssetEmbeddingMapper embeddingMapper,
                                       AssetEmbeddingCache embeddingCache,
                                       VectorSearchMapper vectorSearchMapper,
                                       VectorProperties vectorProperties,
                                       AssetMapper assetMapper,
                                       AssetSimilarityEdgeMapper edgeMapper,
                                       ObjectMapper objectMapper,
                                       GraphSettingsService settingsService) {
        this.embeddingMapper = embeddingMapper;
        this.embeddingCache = embeddingCache;
        this.vectorSearchMapper = vectorSearchMapper;
        this.vectorProperties = vectorProperties;
        this.assetMapper = assetMapper;
        this.edgeMapper = edgeMapper;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    public record Neighbor(long assetId, double score) {
    }

    /** 启动时若图为空且有分析完成的资产，自动后台重建一次（不阻塞启动）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void autoBuildOnStartup() {
        if (!settingsService.effective().enabled() || edgeMapper.count() > 0 || embeddingCache.all().isEmpty()) {
            return;
        }
        Thread thread = new Thread(this::rebuildAll, "similarity-graph-autobuild");
        thread.setDaemon(true);
        thread.start();
    }

    public int rebuildAll() {
        GraphSettings settings = settingsService.effective();
        if (!settings.enabled() || !rebuilding.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<Long> assetIds = embeddingCache.all().values().stream()
                    .map(AssetEmbeddingCache.Snapshot::assetId)
                    .toList();
            int built = 0;
            for (Long assetId : assetIds) {
                try {
                    rebuildEdges(assetId);
                    built++;
                } catch (Exception e) {
                    log.warn("资产 {} 相似图边重建失败: {}", assetId, e.getMessage());
                }
            }
            log.info("相似度图重建完成：{} 张资产，{} 条边", built, edgeMapper.count());
            return built;
        } finally {
            rebuilding.set(false);
        }
    }

    /** 为单个资产重建全部边（先删后建，幂等；嵌入更新后调用）。 */
    public void rebuildEdges(long assetId) {
        GraphSettings settings = settingsService.effective();
        if (!settings.enabled()) {
            return;
        }
        AssetEmbedding embedding = embeddingMapper.selectById(assetId);
        if (embedding == null || !AssetEmbedding.STATUS_DONE.equals(embedding.getStatus())) {
            edgeMapper.deleteForAsset(assetId);
            return;
        }
        // 通用域（真人 / 风景 / 截图）不吃 WD14 特征：Danbooru 特征在这种图上的近邻几乎全是噪声，
        // 只保留 CLIP 通道，且边权直接取 CLIP 相似度，不再被双通道权重稀释。
        boolean generalDomain = AssetEmbedding.DOMAIN_GENERAL.equals(embedding.getDomain());
        Map<Long, Map<String, Double>> channelScores = new LinkedHashMap<>();
        if (!generalDomain) {
            collectChannel(channelScores, embedding.getImageEmbedding(), assetId, "wd14", settings);
        }
        collectChannel(channelScores, embedding.getClipEmbedding(), assetId, "clip", settings);
        Set<Long> active = activeAssetIds(channelScores.keySet());
        List<Map.Entry<Long, Double>> merged = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Double>> entry : channelScores.entrySet()) {
            long neighborId = entry.getKey();
            if (!active.contains(neighborId)) {
                continue;
            }
            Map<String, Double> channels = entry.getValue();
            Double wd14 = channels.get("wd14");
            Double clip = channels.get("clip");
            double score;
            if (generalDomain) {
                score = clip == null ? 0.0 : clip;
            } else if (wd14 != null && clip != null) {
                score = settings.wd14Weight() * wd14 + settings.clipWeight() * clip;
            } else if (wd14 != null) {
                score = wd14 * settings.singleChannelDiscount();
            } else {
                score = clip == null ? 0.0 : clip * settings.singleChannelDiscount();
            }
            if (score >= settings.edgeThreshold()) {
                merged.add(Map.entry(neighborId, score));
            }
        }
        merged.sort(Map.Entry.<Long, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        edgeMapper.deleteForAsset(assetId);
        Instant now = Instant.now();
        for (Map.Entry<Long, Double> entry : merged.stream().limit(settings.maxEdgesPerAsset()).toList()) {
            long aId = Math.min(assetId, entry.getKey());
            long bId = Math.max(assetId, entry.getKey());
            String channelsJson = VectorUtils.toJson(channelScores.get(entry.getKey()), objectMapper);
            edgeMapper.insert(aId, bId, entry.getValue(), channelsJson, now);
        }
    }

    /** 无向邻居（按边权降序；已过滤软删除资产）。 */
    public List<Neighbor> neighborsOf(long assetId, int limit) {
        return neighborsOf(assetId, limit, 0.0);
    }

    public List<Neighbor> neighborsOf(long assetId, int limit, double minScore) {
        if (!settingsService.effective().enabled()) {
            return List.of();
        }
        return edgeMapper.neighborsOf(assetId, minScore, Math.max(1, limit)).stream()
                .map(row -> new Neighbor(row.neighborId(), row.score()))
                .toList();
    }

    /** 给定资产集合中、边权达到阈值的近重复对（双向对只返回一条）。 */
    public List<long[]> strongPairs(List<Long> assetIds, double minScore) {
        if (!settingsService.effective().enabled()) {
            return List.of();
        }
        if (assetIds == null || assetIds.size() < 2) {
            return List.of();
        }
        return edgeMapper.strongPairs(assetIds, minScore).stream()
                .map(row -> new long[]{row.aId(), row.bId()})
                .toList();
    }

    /** 无向边总数（双向对只计一条）。 */
    public long edgeCount() {
        return edgeMapper.count();
    }

    public boolean isEmpty() {
        return edgeMapper.count() == 0;
    }

    private void collectChannel(Map<Long, Map<String, Double>> channelScores, String vectorJson,
                                long selfId, String channel, GraphSettings settings) {
        if (vectorJson == null || vectorJson.isBlank()) {
            return;
        }
        for (ImageVectorMatch match : fetchNeighbors(vectorJson, selfId, "wd14".equals(channel), settings)) {
            double raw = match.similarity() == null ? -1 : match.similarity();
            if (raw < 0) {
                continue;
            }
            double normalized = (raw + 1) / 2;
            channelScores.computeIfAbsent(match.assetId(), key -> new LinkedHashMap<>()).put(channel, normalized);
        }
    }

    private List<ImageVectorMatch> fetchNeighbors(String vectorJson, long selfId, boolean wd14, GraphSettings settings) {
        if (vectorProperties.enabled()) {
            try {
                return wd14
                        ? vectorSearchMapper.similarAssets(vectorJson, selfId, settings.fetchPerChannel())
                        : vectorSearchMapper.similarClipAssets(vectorJson, selfId, settings.fetchPerChannel());
            } catch (Exception e) {
                log.warn("向量索引近邻失败，回退内存计算: {}", e.getMessage());
            }
        }
        double[] query = VectorUtils.fromJson(vectorJson, objectMapper);
        if (query == null) {
            return List.of();
        }
        List<ImageVectorMatch> rows = new ArrayList<>();
        for (AssetEmbeddingCache.Snapshot snapshot : embeddingCache.all().values()) {
            if (snapshot.assetId() == selfId) {
                continue;
            }
            double[] candidate = wd14 ? snapshot.image() : snapshot.clip();
            if (candidate == null) {
                continue;
            }
            rows.add(new ImageVectorMatch(snapshot.assetId(), VectorUtils.cosine(query, candidate)));
        }
        rows.sort(Comparator.comparingDouble((ImageVectorMatch row) ->
                row.similarity() == null ? -1 : row.similarity()).reversed()
                .thenComparing(ImageVectorMatch::assetId));
        return rows.stream().limit(settings.fetchPerChannel()).toList();
    }

    private Set<Long> activeAssetIds(Set<Long> candidateIds) {
        if (candidateIds.isEmpty()) {
            return Set.of();
        }
        return assetMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<Asset>lambdaQuery()
                        .in(Asset::getId, candidateIds)
                        .eq(Asset::getDeleted, false))
                .stream()
                .map(Asset::getId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
