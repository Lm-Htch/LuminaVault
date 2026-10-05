package com.lumi.vault.business.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.client.AiClient;
import com.lumi.vault.business.agent.config.AiProperties;
import com.lumi.vault.business.agent.dto.AgentSearchRequest;
import com.lumi.vault.business.agent.dto.AgentSearchResponse;
import com.lumi.vault.business.agent.dto.AgentSearchResult;
import com.lumi.vault.business.agent.entity.AssetFeedback;
import com.lumi.vault.business.agent.event.RecommendationInvalidatedEvent;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.mapper.AssetFeedbackMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.character.entity.Character;
import com.lumi.vault.business.character.service.CharacterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AgentSearchService {

    private static final Logger log = LoggerFactory.getLogger(AgentSearchService.class);
    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final double USER_SIM_WEIGHT = 0.15;
    private static final double AFFINITY_WEIGHT = 0.1;
    private static final double CHARACTER_WEIGHT = 0.35;
    private static final double LLM_WEIGHT = 0.6;

    private static final String RERANK_SYSTEM_PROMPT = """
            你是图片检索助手。用户给出搜索词和候选图片列表，请判断每张图片与搜索词的匹配程度。
            只输出 JSON，不要输出其他内容：
            {"comment":"一句话说明","results":[{"id":1,"confidence":0.92}]}
            confidence 为 0 到 1 的小数，结果按 confidence 从高到低排序，只保留真正相关的图片。""";

    private final AssetMapper assetMapper;
    private final AssetEmbeddingCache embeddingCache;
    private final AssetFeedbackMapper feedbackMapper;
    private final AiClient aiClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final CharacterService characterService;
    private final ConcurrentHashMap<String, CachedResponse> queryCache = new ConcurrentHashMap<>();

    private record CachedResponse(AgentSearchResponse response, Instant expiresAt) {
    }

    public AgentSearchService(AssetMapper assetMapper,
                              AssetEmbeddingCache embeddingCache,
                              AssetEmbeddingMapper embeddingMapper,
                              AssetFeedbackMapper feedbackMapper,
                              AiClient aiClient,
                              AiProperties properties,
                              ObjectMapper objectMapper,
                              CharacterService characterService) {
        this.assetMapper = assetMapper;
        this.embeddingCache = embeddingCache;
        this.feedbackMapper = feedbackMapper;
        this.aiClient = aiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.characterService = characterService;
    }

    @EventListener(RecommendationInvalidatedEvent.class)
    public void invalidateCache() {
        queryCache.clear();
    }

    public AgentSearchResponse search(long userId, AgentSearchRequest request) {
        String query = request.query().trim();
        int limit = request.limit() == null ? 10 : request.limit();
        String cacheKey = userId + "|" + query + "|" + limit;
        CachedResponse cached = queryCache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.response();
        }

        Map<Long, Asset> assets = activeAssets();
        List<AssetEmbeddingCache.Snapshot> embeddings = embeddingCache.all().values().stream()
                .filter(snapshot -> assets.containsKey(snapshot.assetId()))
                .toList();

        Map<Long, Double> affinity = new HashMap<>();
        Map<Long, List<double[]>> userVectors = new HashMap<>();
        for (AssetFeedback feedback : feedbackMapper.selectList(Wrappers.<AssetFeedback>lambdaQuery()
                .eq(AssetFeedback::getUserId, userId))) {
            double signed = AssetFeedback.ACTION_SELECT.equals(feedback.getAction())
                    ? feedback.getWeight() : -feedback.getWeight();
            affinity.merge(feedback.getAssetId(), signed, Double::sum);
            if (feedback.getEmbedding() != null) {
                double[] vector = VectorUtils.fromJson(feedback.getEmbedding(), objectMapper);
                if (vector != null) {
                    userVectors.computeIfAbsent(feedback.getAssetId(), key -> new ArrayList<>()).add(vector);
                }
            }
        }

        Character queryCharacter = characterService.resolveByQuery(query);
        Long queryCharacterId = queryCharacter == null ? null : queryCharacter.getId();
        Map<Long, String> characterNames = characterService.nameMap();

        double[] queryVector = embedQuery(query);
        List<Scored> scored = queryVector != null
                ? scoreByVector(queryVector, embeddings, assets, affinity, userVectors, queryCharacterId)
                : scoreByKeyword(query, assets, embeddings, affinity, queryCharacterId);
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        int candidateCount = Math.min(scored.size(), Math.max(limit * CANDIDATE_MULTIPLIER, limit));
        List<Scored> candidates = new ArrayList<>(scored.subList(0, candidateCount));

        String comment = null;
        if (queryVector != null && properties.rerank() && aiClient.chatEnabled() && !candidates.isEmpty()) {
            RerankResult rerank = rerank(query, candidates);
            if (rerank != null) {
                for (Scored candidate : candidates) {
                    Double llmConfidence = rerank.confidences().get(candidate.asset().getId());
                    if (llmConfidence != null) {
                        candidate.setScore(clamp01(LLM_WEIGHT * llmConfidence + (1 - LLM_WEIGHT) * candidate.score()));
                    }
                }
                candidates.sort(Comparator.comparingDouble(Scored::score).reversed());
                comment = rerank.comment();
            }
        }

        List<AgentSearchResult> results = candidates.stream()
                .limit(limit)
                .map(candidate -> toResult(candidate, characterNames))
                .toList();
        AgentSearchResponse response = new AgentSearchResponse(query, results, comment, aiClient.available());
        if (properties.searchCacheTtl() != null && !properties.searchCacheTtl().isZero()
                && !properties.searchCacheTtl().isNegative()) {
            queryCache.put(cacheKey, new CachedResponse(response, Instant.now().plus(properties.searchCacheTtl())));
        }
        return response;
    }

    private List<Scored> scoreByVector(double[] queryVector,
                                       List<AssetEmbeddingCache.Snapshot> embeddings,
                                       Map<Long, Asset> assets,
                                       Map<Long, Double> affinity,
                                       Map<Long, List<double[]>> userVectors,
                                       Long queryCharacterId) {
        List<Scored> scored = new ArrayList<>();
        for (AssetEmbeddingCache.Snapshot snapshot : embeddings) {
            if (snapshot.text() == null) {
                continue;
            }
            double base = (VectorUtils.cosine(queryVector, snapshot.text()) + 1) / 2;
            double userSimilarity = 0;
            for (double[] userVector : userVectors.getOrDefault(snapshot.assetId(), List.of())) {
                userSimilarity = Math.max(userSimilarity, VectorUtils.cosine(queryVector, userVector));
            }
            double characterBoost = queryCharacterId != null
                    && queryCharacterId.equals(assets.get(snapshot.assetId()).getCharacterId())
                    ? CHARACTER_WEIGHT : 0;
            double score = base
                    + USER_SIM_WEIGHT * Math.max(userSimilarity, 0)
                    + AFFINITY_WEIGHT * Math.tanh(affinity.getOrDefault(snapshot.assetId(), 0.0))
                    + characterBoost;
            scored.add(new Scored(snapshot, assets.get(snapshot.assetId()), clamp01(score)));
        }
        return scored;
    }

    private List<Scored> scoreByKeyword(String query,
                                        Map<Long, Asset> assets,
                                        List<AssetEmbeddingCache.Snapshot> embeddings,
                                        Map<Long, Double> affinity,
                                        Long queryCharacterId) {
        String needle = query.toLowerCase(Locale.ROOT);
        Map<Long, AssetEmbeddingCache.Snapshot> embeddingByAsset = embeddings.stream()
                .collect(Collectors.toMap(AssetEmbeddingCache.Snapshot::assetId, snapshot -> snapshot, (left, right) -> left));
        List<Scored> scored = new ArrayList<>();
        for (Asset asset : assets.values()) {
            AssetEmbeddingCache.Snapshot snapshot = embeddingByAsset.get(asset.getId());
            boolean descriptionMatch = snapshot != null
                    && (containsIgnoreCase(snapshot.description(), needle)
                    || containsIgnoreCase(snapshot.tags(), needle));
            boolean filenameMatch = containsIgnoreCase(asset.getOriginalFilename(), needle);
            boolean characterMatch = queryCharacterId != null && queryCharacterId.equals(asset.getCharacterId());
            if (!descriptionMatch && !filenameMatch && !characterMatch) {
                continue;
            }
            double base = descriptionMatch ? 0.9 : characterMatch ? 0.7 : 0.6;
            double score = clamp01(base + AFFINITY_WEIGHT * Math.tanh(affinity.getOrDefault(asset.getId(), 0.0)));
            if (snapshot == null) {
                snapshot = new AssetEmbeddingCache.Snapshot(asset.getId(), null, null, null, null, null, null);
            }
            scored.add(new Scored(snapshot, asset, score));
        }
        return scored;
    }

    private RerankResult rerank(String query, List<Scored> candidates) {
        try {
            StringBuilder prompt = new StringBuilder("搜索词：").append(query).append("\n候选图片：\n");
            for (int i = 0; i < candidates.size(); i++) {
                Scored candidate = candidates.get(i);
                prompt.append(i)
                        .append(". id=").append(candidate.asset().getId())
                        .append(" 文件名=").append(nullToEmpty(candidate.asset().getOriginalFilename()))
                        .append(" 描述=").append(nullToEmpty(candidate.snapshot().description()))
                        .append(" 标签=").append(nullToEmpty(candidate.snapshot().tags()))
                        .append('\n');
            }
            JsonNode root = objectMapper.readTree(stripCodeFence(aiClient.complete(RERANK_SYSTEM_PROMPT, prompt.toString())));
            Map<Long, Double> confidences = new HashMap<>();
            for (JsonNode item : root.path("results")) {
                confidences.put(item.path("id").asLong(), clamp01(item.path("confidence").asDouble(0)));
            }
            String comment = root.path("comment").asString("");
            return new RerankResult(confidences, comment.isBlank() ? null : comment);
        } catch (Exception e) {
            log.warn("Agent 二次确认失败: {}", e.getMessage());
            return null;
        }
    }

    private AgentSearchResult toResult(Scored scored, Map<Long, String> characterNames) {
        Asset asset = scored.asset();
        AssetEmbeddingCache.Snapshot snapshot = scored.snapshot();
        Long characterId = asset.getCharacterId();
        return new AgentSearchResult(
                asset.getId(),
                asset.getSha256(),
                asset.getOriginalFilename(),
                Math.round(scored.score() * 10000) / 10000.0,
                snapshot.description(),
                VectorUtils.stringsFromJson(snapshot.tags(), objectMapper),
                characterId,
                characterId == null ? null : characterNames.get(characterId));
    }

    private double[] embedQuery(String query) {
        if (!aiClient.embeddingEnabled()) {
            return null;
        }
        try {
            return VectorUtils.toArray(aiClient.embed(query));
        } catch (Exception e) {
            log.warn("查询向量化失败，降级为关键字匹配: {}", e.getMessage());
            return null;
        }
    }

    private Map<Long, Asset> activeAssets() {
        return assetMapper.selectList(Wrappers.<Asset>lambdaQuery().eq(Asset::getDeleted, false))
                .stream()
                .collect(Collectors.toMap(Asset::getId, asset -> asset, (left, right) -> left));
    }

    private boolean containsIgnoreCase(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String stripCodeFence(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.trim();
        if (cleaned.startsWith("```")) {
            int firstLine = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                cleaned = cleaned.substring(firstLine + 1, lastFence).trim();
            }
        }
        return cleaned;
    }

    private double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static final class Scored {

        private final AssetEmbeddingCache.Snapshot snapshot;
        private final Asset asset;
        private double score;

        Scored(AssetEmbeddingCache.Snapshot snapshot, Asset asset, double score) {
            this.snapshot = snapshot;
            this.asset = asset;
            this.score = score;
        }

        AssetEmbeddingCache.Snapshot snapshot() {
            return snapshot;
        }

        Asset asset() {
            return asset;
        }

        double score() {
            return score;
        }

        void setScore(double score) {
            this.score = score;
        }
    }

    private record RerankResult(Map<Long, Double> confidences, String comment) {
    }
}
