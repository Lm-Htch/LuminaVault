package com.lumi.vault.business.asset.service;

import lombok.extern.slf4j.Slf4j;


import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import com.lumi.vault.business.asset.dto.SimilarAssetResponse;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.graph.service.AssetSimilarityGraphService;
import com.lumi.vault.business.model.client.ImageModelClient;
import com.lumi.vault.business.model.config.ModelProperties;
import com.lumi.vault.business.model.dto.ModelAnalysis;
import com.lumi.vault.business.model.dto.ModelTag;
import com.lumi.vault.business.vector.dto.ImageVectorMatch;
import com.lumi.vault.business.vector.mapper.VectorSearchMapper;
import com.lumi.vault.config.VectorProperties;
import com.lumi.vault.exception.ApiException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 本地以图搜图：WD14 图像向量 + CLIP 通用向量 + 标签相似度三路召回，RRF 融合。
 * vector 开启时走 pgvector ANN，否则回退内存余弦（测试/小库环境）；标签按 IDF 加权余弦（纯内存）。
 */
@Service
@Slf4j
public class AssetSimilarityService {
    private static final int DEFAULT_LIMIT = 12;
    private static final int MAX_LIMIT = 50;
    private static final int ROUTE_LIMIT = 200;
    private static final double RRF_K = 60.0;
    private static final double MIN_TAG_SIMILARITY = 0.12;
    private static final double TAG_ROUTE_WEIGHT = 2.5;

    private final AssetMapper assetMapper;
    private final AssetEmbeddingMapper embeddingMapper;
    private final AssetEmbeddingCache embeddingCache;
    private final VectorSearchMapper vectorSearchMapper;
    private final VectorProperties vectorProperties;
    private final ImageModelClient imageModelClient;
    private final ModelProperties modelProperties;
    private final ObjectMapper objectMapper;
    private final AssetSimilarityGraphService similarityGraphService;

    public AssetSimilarityService(AssetMapper assetMapper,
                                  AssetEmbeddingMapper embeddingMapper,
                                  AssetEmbeddingCache embeddingCache,
                                  VectorSearchMapper vectorSearchMapper,
                                  VectorProperties vectorProperties,
                                  ImageModelClient imageModelClient,
                                  ModelProperties modelProperties,
                                  ObjectMapper objectMapper,
                                  AssetSimilarityGraphService similarityGraphService) {
        this.assetMapper = assetMapper;
        this.embeddingMapper = embeddingMapper;
        this.embeddingCache = embeddingCache;
        this.vectorSearchMapper = vectorSearchMapper;
        this.vectorProperties = vectorProperties;
        this.imageModelClient = imageModelClient;
        this.modelProperties = modelProperties;
        this.objectMapper = objectMapper;
        this.similarityGraphService = similarityGraphService;
    }

    public List<SimilarAssetResponse> similarByAsset(long assetId, int limit) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
            throw ApiException.notFound("资产不存在");
        }
        AssetEmbedding embedding = embeddingMapper.selectById(assetId);
        if (embedding == null) {
            return List.of();
        }
        List<String> queryTags = parseTags(embedding.getTags());
        List<SimilarAssetResponse> result = searchFused(embedding.getImageEmbedding(), embedding.getClipEmbedding(),
                queryTags, assetId, clampLimit(limit));
        if (result.isEmpty()) {
            // 向量/标签均不可用（如模型服务离线或未分析）时回退到相似图邻居
            return graphFallback(assetId, clampLimit(limit));
        }
        return result;
    }

    public List<SimilarAssetResponse> similarByImage(byte[] image, String contentType, int limit) {
        if (image == null || image.length == 0) {
            throw ApiException.badRequest("图片不能为空");
        }
        List<Double> wd14 = imageModelClient.embed(image, contentType);
        List<Double> clip = imageModelClient.clipEmbed(image, contentType);
        List<String> queryTags = analyzeTags(image, contentType);
        String wd14Json = wd14 == null || wd14.isEmpty() ? null : VectorUtils.toJson(wd14, objectMapper);
        String clipJson = clip == null || clip.isEmpty() ? null : VectorUtils.toJson(clip, objectMapper);
        if (wd14Json == null && clipJson == null && queryTags.isEmpty()) {
            throw ApiException.badRequest("模型服务未启用或未返回图像向量，无法以图搜图");
        }
        return searchFused(wd14Json, clipJson, queryTags, -1, clampLimit(limit));
    }

    private List<String> analyzeTags(byte[] image, String contentType) {
        if (!imageModelClient.available()) {
            return List.of();
        }
        try {
            ModelAnalysis analysis = imageModelClient.analyze(image, contentType);
            if (analysis == null || analysis.tags() == null) {
                return List.of();
            }
            return analysis.tags().stream()
                    .filter(tag -> tag.confidence() >= modelProperties.tagConfidence())
                    .map(ModelTag::tag)
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(tag -> tag.toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("以图搜图标签解析失败，跳过标签相似度: {}", e.getMessage());
            return List.of();
        }
    }

    private record SimilarMatch(long assetId, double rankScore, double similarity) {
    }

    private record WeightedRoute(Map<Long, Double> scores, double weight) {
    }

    private List<SimilarAssetResponse> searchFused(String wd14Json, String clipJson, List<String> queryTags,
                                                   long excludeId, int limit) {
        List<WeightedRoute> routes = new ArrayList<>();
        if (wd14Json != null && !wd14Json.isBlank()) {
            Map<Long, Double> route = routeFor(wd14Json, excludeId, true);
            if (!route.isEmpty()) {
                routes.add(new WeightedRoute(route, 1.0));
            }
        }
        if (clipJson != null && !clipJson.isBlank()) {
            Map<Long, Double> route = routeFor(clipJson, excludeId, false);
            if (!route.isEmpty()) {
                routes.add(new WeightedRoute(route, 1.0));
            }
        }
        Map<Long, Double> tagRoute = tagRoute(queryTags, excludeId);
        if (!tagRoute.isEmpty()) {
            routes.add(new WeightedRoute(tagRoute, TAG_ROUTE_WEIGHT));
        }
        if (routes.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> fused = new HashMap<>();
        Map<Long, Double> display = new HashMap<>();
        double maxScore = 0;
        for (WeightedRoute route : routes) {
            maxScore += route.weight();
            List<Map.Entry<Long, Double>> ranked = route.scores().entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .toList();
            for (int index = 0; index < ranked.size(); index++) {
                Map.Entry<Long, Double> entry = ranked.get(index);
                fused.merge(entry.getKey(), route.weight() / (RRF_K + index + 1), Double::sum);
                display.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        double max = maxScore / (RRF_K + 1.0);
        List<SimilarMatch> matches = fused.entrySet().stream()
                .map(entry -> new SimilarMatch(entry.getKey(), entry.getValue() / max,
                        display.getOrDefault(entry.getKey(), 0.0)))
                .sorted(Comparator.comparingDouble(SimilarMatch::rankScore).reversed()
                        .thenComparing(SimilarMatch::assetId))
                .limit(limit)
                .toList();
        return toResponses(matches);
    }

    private Map<Long, Double> routeFor(String vectorJson, long excludeId, boolean wd14) {
        if (vectorProperties.enabled()) {
            try {
                List<ImageVectorMatch> rows = wd14
                        ? vectorSearchMapper.similarAssets(vectorJson, excludeId, ROUTE_LIMIT)
                        : vectorSearchMapper.similarClipAssets(vectorJson, excludeId, ROUTE_LIMIT);
                Map<Long, Double> route = new HashMap<>();
                for (ImageVectorMatch row : rows) {
                    double raw = row.similarity() == null ? -1.0 : row.similarity();
                    route.put(row.assetId(), (raw + 1) / 2);
                }
                return route;
            } catch (Exception e) {
                log.warn("向量索引检索失败，回退内存检索: {}", e.getMessage());
            }
        }
        double[] vector = VectorUtils.fromJson(vectorJson, objectMapper);
        if (vector == null) {
            return Map.of();
        }
        Map<Long, Asset> assets = activeAssets();
        Map<Long, Double> route = new HashMap<>();
        for (AssetEmbeddingCache.Snapshot snapshot : embeddingCache.all().values()) {
            double[] candidate = wd14 ? snapshot.image() : snapshot.clip();
            if (candidate == null || snapshot.assetId() == excludeId || !assets.containsKey(snapshot.assetId())) {
                continue;
            }
            route.put(snapshot.assetId(), (VectorUtils.cosine(vector, candidate) + 1) / 2);
        }
        return route;
    }

    /**
     * 标签相似度：稀有标签权重高（BM25 式 IDF），评分为「候选覆盖了查询多少加权标签」。
     * 纯内存计算，毫秒级；被 2.5 倍加权融合，确保标签强匹配能与向量路线竞争。
     */
    private Map<Long, Double> tagRoute(List<String> queryTags, long excludeId) {
        if (queryTags == null || queryTags.isEmpty()) {
            return Map.of();
        }
        Set<String> query = new LinkedHashSet<>(queryTags);
        Map<Long, Asset> assets = activeAssets();
        List<Map.Entry<Long, List<String>>> docs = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (AssetEmbeddingCache.Snapshot snapshot : embeddingCache.all().values()) {
            if (snapshot.assetId() == excludeId || !assets.containsKey(snapshot.assetId())) {
                continue;
            }
            List<String> tags = parseTags(snapshot.tags());
            if (tags.isEmpty()) {
                continue;
            }
            docs.add(Map.entry(snapshot.assetId(), tags));
            for (String tag : new HashSet<>(tags)) {
                documentFrequency.merge(tag, 1, Integer::sum);
            }
        }
        if (docs.isEmpty()) {
            return Map.of();
        }
        double total = docs.size();
        Map<String, Double> idfCache = new HashMap<>();
        Function<String, Double> idf = tag -> idfCache.computeIfAbsent(tag, key -> Math.max(0.05,
                Math.log((total + 1) / (documentFrequency.getOrDefault(key, 0) + 0.5))));
        double queryWeight = 0;
        for (String tag : query) {
            queryWeight += idf.apply(tag);
        }
        if (queryWeight <= 0) {
            return Map.of();
        }
        Map<Long, Double> route = new HashMap<>();
        for (Map.Entry<Long, List<String>> doc : docs) {
            double overlap = 0;
            for (String tag : new HashSet<>(doc.getValue())) {
                if (query.contains(tag)) {
                    overlap += idf.apply(tag);
                }
            }
            if (overlap <= 0) {
                continue;
            }
            double score = overlap / queryWeight;
            if (score >= MIN_TAG_SIMILARITY) {
                route.put(doc.getKey(), score);
            }
        }
        return route;
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return VectorUtils.stringsFromJson(tagsJson, objectMapper).stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(tag -> tag.toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 「找相似」向量检索为空时回退到相似图邻居（稳定、无需模型服务在线）。
     */
    private List<SimilarAssetResponse> graphFallback(long assetId, int limit) {
        List<AssetSimilarityGraphService.Neighbor> neighbors = similarityGraphService.neighborsOf(assetId, limit);
        if (neighbors.isEmpty()) {
            return List.of();
        }
        List<Long> ids = neighbors.stream().map(AssetSimilarityGraphService.Neighbor::assetId).toList();
        Map<Long, Asset> assets = assetMapper.selectList(Wrappers.<Asset>lambdaQuery()
                        .in(Asset::getId, ids)
                        .eq(Asset::getDeleted, false))
                .stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity(), (left, right) -> left));
        List<SimilarAssetResponse> responses = new ArrayList<>();
        for (AssetSimilarityGraphService.Neighbor neighbor : neighbors) {
            Asset asset = assets.get(neighbor.assetId());
            if (asset == null) {
                continue;
            }
            responses.add(new SimilarAssetResponse(asset.getId(), asset.getOriginalFilename(), asset.getSha256(),
                    Math.round(neighbor.score() * 10000) / 10000.0));
        }
        return responses;
    }

    private List<SimilarAssetResponse> toResponses(List<SimilarMatch> matches) {
        if (matches.isEmpty()) {
            return List.of();
        }
        List<Long> ids = matches.stream().map(SimilarMatch::assetId).toList();
        Map<Long, Asset> assets = assetMapper.selectList(Wrappers.<Asset>lambdaQuery()
                        .in(Asset::getId, ids)
                        .eq(Asset::getDeleted, false))
                .stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity(), (left, right) -> left));
        List<SimilarAssetResponse> responses = new ArrayList<>();
        for (SimilarMatch match : matches) {
            Asset asset = assets.get(match.assetId());
            if (asset == null) {
                continue;
            }
            responses.add(new SimilarAssetResponse(asset.getId(), asset.getOriginalFilename(), asset.getSha256(),
                    Math.round(match.similarity() * 10000) / 10000.0));
        }
        return responses;
    }

    private Map<Long, Asset> activeAssets() {
        return assetMapper.selectList(Wrappers.<Asset>lambdaQuery().eq(Asset::getDeleted, false))
                .stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity(), (left, right) -> left));
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
