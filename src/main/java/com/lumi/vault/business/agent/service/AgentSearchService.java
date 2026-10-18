package com.lumi.vault.business.agent.service;

import lombok.extern.slf4j.Slf4j;


import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.cache.AssetEmbeddingCache;
import com.lumi.vault.business.agent.client.AiClient;
import com.lumi.vault.business.agent.client.ImageAnalysis;
import com.lumi.vault.business.agent.client.VisionImage;
import com.lumi.vault.business.agent.config.AiProperties;
import com.lumi.vault.business.agent.dto.AgentSearchRequest;
import com.lumi.vault.business.agent.dto.AgentSearchResponse;
import com.lumi.vault.business.agent.dto.AgentSearchResult;
import com.lumi.vault.business.agent.dto.SearchProgressEvent;
import com.lumi.vault.business.agent.entity.AssetFeedback;
import com.lumi.vault.business.agent.event.RecommendationInvalidatedEvent;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.mapper.AssetFeedbackMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.entity.AssetMetadata;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.asset.storage.AssetStorage;
import com.lumi.vault.business.character.entity.Character;
import com.lumi.vault.business.character.service.CharacterService;
import com.lumi.vault.business.folder.service.FolderService;
import com.lumi.vault.business.graph.service.AssetSimilarityGraphService;
import com.lumi.vault.business.graph.service.GraphSettingsService;
import com.lumi.vault.business.model.client.ImageModelClient;
import com.lumi.vault.business.model.config.ModelProperties;
import com.lumi.vault.business.model.dto.ModelAnalysis;
import com.lumi.vault.business.model.dto.ModelTag;
import com.lumi.vault.business.perception.PerceptionAggregator;
import com.lumi.vault.business.perception.PerceptionLexicon;
import com.lumi.vault.business.perception.PerceptionProfile;
import com.lumi.vault.common.ImageUtils;
import com.lumi.vault.exception.ApiException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentSearchService {
    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final double USER_SIM_WEIGHT = 0.15;
    private static final double AFFINITY_WEIGHT = 0.1;
    private static final double CHARACTER_WEIGHT = 0.35;
    private static final double LLM_WEIGHT = 0.6;
    private static final double RRF_K = 60.0;
    private static final double FUZZY_THRESHOLD = 0.2;
    private static final int MAX_RERANK_DOC_CHARS = 300;
    private static final int MAX_RERANK_CANDIDATES = 30;
    private static final int MAX_QUERY_VECTORS = 5;
    private static final int VECTOR_CACHE_MAX = 2000;
    private static final double VERIFY_WEIGHT = 0.7;
    private static final double PROFILE_ROUTE_WEIGHT = 1.2;
    private static final double CLIP_TEXT_ROUTE_WEIGHT = 1.5;
    private static final int MAX_CLIP_TEXT_CHARS = 30;
    private static final double FACET_AFFINITY_WEIGHT = 0.15;
    private static final double WALLPAPER_BONUS_WEIGHT = 0.25;
    private static final int MAX_VERIFY_IMAGE_BYTES = 1_500_000;
    private static final int VERIFY_MAX_SIDE = 512;
    private static final int PASTED_VISION_MAX_SIDE = 1024;
    private static final Consumer<SearchProgressEvent> NOOP_PROGRESS = event -> {
    };

    private static final Map<String, String> HAIR_FAMILY_ALIASES = Map.ofEntries(
            Map.entry("white", "white"), Map.entry("silver", "white"), Map.entry("gray", "white"),
            Map.entry("grey", "white"), Map.entry("black", "black"), Map.entry("blue", "blue"),
            Map.entry("cyan", "blue"), Map.entry("purple", "purple"), Map.entry("pink", "pink"),
            Map.entry("yellow", "blonde"), Map.entry("blonde", "blonde"), Map.entry("brown", "brown"),
            Map.entry("red", "red"), Map.entry("green", "green"), Map.entry("orange", "orange"));

    private static final Map<String, List<String>> HAIR_TAG_FAMILIES = Map.ofEntries(
            Map.entry("white", List.of("white_hair", "silver_hair", "grey_hair", "gray_hair")),
            Map.entry("black", List.of("black_hair")),
            Map.entry("blue", List.of("blue_hair", "aqua_hair")),
            Map.entry("purple", List.of("purple_hair")),
            Map.entry("pink", List.of("pink_hair")),
            Map.entry("blonde", List.of("blonde")),
            Map.entry("brown", List.of("brown_hair")),
            Map.entry("red", List.of("red_hair")),
            Map.entry("green", List.of("green_hair")),
            Map.entry("orange", List.of("orange_hair")));

    private static final Map<String, List<String>> HAIR_WORD_FAMILIES = Map.ofEntries(
            Map.entry("white", List.of("白发", "白毛", "银发", "银白色发", "银白长发", "灰发")),
            Map.entry("black", List.of("黑发", "黑毛")),
            Map.entry("blue", List.of("蓝发", "青发", "蓝毛")),
            Map.entry("purple", List.of("紫发", "紫毛")),
            Map.entry("pink", List.of("粉发", "粉毛")),
            Map.entry("blonde", List.of("金发")),
            Map.entry("brown", List.of("棕发", "褐发")),
            Map.entry("red", List.of("红发")),
            Map.entry("green", List.of("绿发")),
            Map.entry("orange", List.of("橙发", "橘发")));

    private static final String RERANK_SYSTEM_PROMPT = """
            你是图片检索助手。用户给出搜索词和候选图片列表，请判断每张图片与搜索词的匹配程度。
            只输出 JSON，不要输出其他内容：
            {"comment":"一句话说明","results":[{"id":1,"confidence":0.92}]}
            confidence 为 0 到 1 的小数，结果按 confidence 从高到低排序，只保留真正相关的图片。""";

    private static final String VERIFY_SYSTEM_PROMPT = """
            你是图片检索的最终确认员。用户给出搜索描述、解析出的检索意图、按当前置信度排序的候选（含元数据与图片本体）。
            请逐张查看图片，判断它是否真正符合用户描述，并修正虚高或虚低的置信度。
            若搜索描述包含主观感受（如温馨、治愈、舒服、梦幻、适合做壁纸等），重点核对画面氛围、色调与用途是否符合。
            只输出 JSON，不要输出其他内容：
            {"comment":"一句话总结","results":[{"id":1,"confidence":0.92,"reason":"简短理由"}]}
            confidence 为 0 到 1 的小数；与描述无关的图片给低分；如果没有任何图片符合描述，comment 里明确说明。""";

    private final AssetMapper assetMapper;
    private final AssetEmbeddingCache embeddingCache;
    private final AssetFeedbackMapper feedbackMapper;
    private final AiClient aiClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final CharacterService characterService;
    private final ImageModelClient imageModelClient;
    private final ModelProperties modelProperties;
    private final LlmQueryAnalyzer queryAnalyzer;
    private final AgentSearchHistoryService historyService;
    private final FolderService folderService;
    private final AssetStorage assetStorage;
    private final AgentSearchAttachmentStore attachmentStore;
    private final AssetSimilarityGraphService similarityGraphService;
    private final GraphSettingsService graphSettingsService;
    private final PerceptionAggregator perceptionAggregator;
    private final PerceptionLexicon perceptionLexicon;
    private final ConcurrentHashMap<String, CachedResponse> queryCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, double[]> vectorCache = new ConcurrentHashMap<>();

    private record CachedResponse(AgentSearchResponse response, Instant expiresAt) {
    }

    public AgentSearchService(AssetMapper assetMapper,
                              AssetEmbeddingCache embeddingCache,
                              AssetEmbeddingMapper embeddingMapper,
                              AssetFeedbackMapper feedbackMapper,
                              AiClient aiClient,
                              AiProperties properties,
                              ObjectMapper objectMapper,
                              CharacterService characterService,
                              ImageModelClient imageModelClient,
                              ModelProperties modelProperties,
                              LlmQueryAnalyzer queryAnalyzer,
                              AgentSearchHistoryService historyService,
                              FolderService folderService,
                              AssetStorage assetStorage,
                              AgentSearchAttachmentStore attachmentStore,
                              AssetSimilarityGraphService similarityGraphService,
                              GraphSettingsService graphSettingsService,
                              PerceptionAggregator perceptionAggregator,
                              PerceptionLexicon perceptionLexicon) {
        this.assetMapper = assetMapper;
        this.embeddingCache = embeddingCache;
        this.feedbackMapper = feedbackMapper;
        this.aiClient = aiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.characterService = characterService;
        this.imageModelClient = imageModelClient;
        this.modelProperties = modelProperties;
        this.queryAnalyzer = queryAnalyzer;
        this.historyService = historyService;
        this.folderService = folderService;
        this.assetStorage = assetStorage;
        this.attachmentStore = attachmentStore;
        this.similarityGraphService = similarityGraphService;
        this.graphSettingsService = graphSettingsService;
        this.perceptionAggregator = perceptionAggregator;
        this.perceptionLexicon = perceptionLexicon;
    }

    @EventListener(RecommendationInvalidatedEvent.class)
    public void invalidateCache() {
        queryCache.clear();
    }

    public AgentSearchResponse search(long userId, AgentSearchRequest request) {
        return search(userId, request, false, NOOP_PROGRESS);
    }

    public AgentSearchResponse search(long userId, AgentSearchRequest request, boolean bypassCache) {
        return search(userId, request, bypassCache, NOOP_PROGRESS);
    }

    public AgentSearchResponse search(long userId, AgentSearchRequest request, boolean bypassCache,
                                      Consumer<SearchProgressEvent> progress) {
        String query = request.query() == null ? "" : request.query().trim();
        AgentSearchAttachmentStore.Attachment attachment = attachmentStore.get(request.attachmentId());
        if (query.isEmpty() && attachment == null) {
            throw ApiException.badRequest(request.attachmentId() == null
                    ? "搜索内容不能为空" : "粘贴图片已过期，请重新粘贴");
        }
        int limit = request.limit() == null ? 10 : request.limit();
        Double minConfidence = request.minConfidence();
        String attachmentKey = attachment == null ? "" : Integer.toHexString(Arrays.hashCode(attachment.data()));
        String cacheKey = userId + "|" + query + "|" + limit + "|" + (minConfidence == null ? "" : minConfidence)
                + "|" + attachmentKey;
        if (!bypassCache) {
            historyService.record(userId, query.isEmpty() ? "[图片搜索]" : query);
            CachedResponse cached = queryCache.get(cacheKey);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                progress.accept(SearchProgressEvent.of("cache", "命中缓存"));
                progress.accept(SearchProgressEvent.of("done",
                        "完成 · 返回 " + cached.response().results().size() + " 条结果"));
                return cached.response();
            }
        }

        // ---- 粘贴图片理解：本地模型（WD14 标签/向量 + OCR）+ LLM 视觉描述 ----
        String imageDescription = null;
        String imageOcr = null;
        List<String> imageTags = new ArrayList<>();
        double[] pastedImageVector = null;
        double[] pastedClipVector = null;
        if (attachment != null) {
            progress.accept(SearchProgressEvent.of("analyze", "读取粘贴图片…"));
            if (imageModelClient.available()) {
                try {
                    ModelAnalysis analysis = imageModelClient.analyze(attachment.data(), attachment.contentType());
                    if (analysis != null) {
                        if (analysis.tags() != null) {
                            for (ModelTag tag : analysis.tags()) {
                                if (tag.confidence() >= modelProperties.tagConfidence()
                                        && tag.tag() != null && !tag.tag().isBlank()) {
                                    imageTags.add(tag.tag().toLowerCase(Locale.ROOT));
                                }
                            }
                        }
                        imageOcr = analysis.ocrText();
                        pastedImageVector = VectorUtils.toArray(analysis.embedding());
                    }
                } catch (Exception e) {
                    log.warn("粘贴图片本地模型分析失败: {}", e.getMessage());
                }
                try {
                    pastedClipVector = VectorUtils.toArray(
                            imageModelClient.clipEmbed(attachment.data(), attachment.contentType()));
                } catch (Exception e) {
                    log.warn("粘贴图片 CLIP 向量计算失败: {}", e.getMessage());
                }
            }
            if (aiClient.visionEnabled()) {
                try {
                    VisionImage visionInput = preparePastedImage(attachment.data(), attachment.contentType());
                    ImageAnalysis analysis = aiClient.analyzeImage(visionInput.data(), visionInput.contentType());
                    if (analysis != null) {
                        imageDescription = analysis.description();
                        if (analysis.tags() != null) {
                            for (String tag : analysis.tags()) {
                                if (tag != null && !tag.isBlank()) {
                                    imageTags.add(tag.toLowerCase(Locale.ROOT));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("粘贴图片视觉理解失败: {}", e.getMessage());
                }
            }
            progress.accept(SearchProgressEvent.of("analyze", "图片理解完成"
                    + (imageDescription == null || imageDescription.isBlank() ? "" : "：" + imageDescription)));
        }

        progress.accept(SearchProgressEvent.of("analyze", "解析查询意图…"));

        Map<Long, Asset> assets = activeAssets();
        Map<Long, AssetEmbeddingCache.Snapshot> snapshots = embeddingCache.all().values().stream()
                .filter(snapshot -> assets.containsKey(snapshot.assetId()))
                .collect(Collectors.toMap(AssetEmbeddingCache.Snapshot::assetId, snapshot -> snapshot, (left, right) -> left));

        Map<Long, Double> affinity = new HashMap<>();
        Map<Long, List<double[]>> userVectors = new HashMap<>();
        Map<Long, Double> feedbackSigns = new HashMap<>();
        for (AssetFeedback feedback : feedbackMapper.selectList(Wrappers.<AssetFeedback>lambdaQuery()
                .eq(AssetFeedback::getUserId, userId))) {
            double signed = AssetFeedback.ACTION_SELECT.equals(feedback.getAction())
                    ? feedback.getWeight() : -feedback.getWeight();
            affinity.merge(feedback.getAssetId(), signed, Double::sum);
            feedbackSigns.merge(feedback.getAssetId(), signed, Double::sum);
            if (feedback.getEmbedding() != null) {
                double[] vector = VectorUtils.fromJson(feedback.getEmbedding(), objectMapper);
                if (vector != null) {
                    userVectors.computeIfAbsent(feedback.getAssetId(), key -> new ArrayList<>()).add(vector);
                }
            }
        }
        // 反馈沿相似图传播（1 跳 ×0.3、2 跳 ×0.1，按边权衰减）
        propagateFeedbackAffinity(affinity, feedbackSigns);
        // 用户感性偏好：从反馈资产画像累计 facet 值偏好，作用于后续打分
        Map<String, Double> facetAffinity = facetAffinityByValue(feedbackSigns, snapshots);

        Map<Long, String> characterNames = characterService.nameMap();
        Map<Long, List<String>> folderNamesByAsset = folderService.folderNamesByAsset();
        String effectiveQuery = buildEffectiveQuery(query, imageDescription, imageOcr, imageTags);
        QueryIntent intent = queryAnalyzer.analyze(effectiveQuery, tagVocabulary(snapshots));
        if (!imageTags.isEmpty()) {
            intent = mergeTerms(intent, imageTags);
        }
        // 感性特征：LLM 意图 facets 与规则词典（查询原文）合并
        Map<String, List<String>> queryFacets = PerceptionLexicon.merge(
                intent.facets(), perceptionLexicon.extract(effectiveQuery));
        progress.accept(SearchProgressEvent.of("analyze", "意图："
                + (intent.positiveTerms().isEmpty() ? "按条件筛选"
                        : String.join(" / ", intent.positiveTerms().stream().limit(4).toList()))
                + (queryFacets.isEmpty() ? ""
                        : " · 感性 " + queryFacets.values().stream().flatMap(List::stream)
                                .distinct().limit(4).collect(Collectors.joining("/")))));

        Character queryCharacter = characterService.resolveByQuery(
                intent.characterHint() != null && !intent.characterHint().isBlank() ? intent.characterHint() : effectiveQuery);
        Long queryCharacterId = queryCharacter == null ? null : queryCharacter.getId();

        Set<Long> excluded = excludedAssets(assets, snapshots, intent, characterNames, folderNamesByAsset);
        List<double[]> queryVectors = embedQueryVectors(effectiveQuery, intent);

        Map<String, Map<Long, Double>> routes = new LinkedHashMap<>();
        if (!queryVectors.isEmpty()) {
            Map<Long, Double> vectorRoute = vectorRoute(queryVectors, snapshots, excluded);
            if (!vectorRoute.isEmpty()) {
                routes.put("vector", vectorRoute);
            }
        }
        Map<Long, Double> tagRoute = tagRoute(intent, snapshots, excluded);
        if (!tagRoute.isEmpty()) {
            routes.put("tag", tagRoute);
        }
        Map<Long, Double> profileRoute = profileRoute(queryFacets, snapshots, excluded);
        if (!profileRoute.isEmpty()) {
            routes.put("profile", profileRoute);
        }
        // CLIP 文本路：感性查询时把描述编码到 CLIP 文本空间，与资产 CLIP 图向量匹配
        if (!queryFacets.isEmpty() && imageModelClient.available()) {
            double[] clipTextVector = clipTextVector(intent, effectiveQuery);
            if (clipTextVector != null) {
                Map<Long, Double> clipTextRoute = imageSimilarityRoute(clipTextVector, snapshots, excluded, false);
                clipTextRoute.values().removeIf(value -> value < 0.6);
                if (!clipTextRoute.isEmpty()) {
                    routes.put("clip_text", clipTextRoute);
                }
            }
        }
        Map<Long, Double> fuzzyRoute = fuzzyRoute(effectiveQuery, intent, assets, snapshots, excluded, characterNames, folderNamesByAsset);
        if (!fuzzyRoute.isEmpty()) {
            routes.put("fuzzy", fuzzyRoute);
        }
        Map<Long, Double> colorRoute = colorRoute(intent, assets, excluded);
        if (!colorRoute.isEmpty()) {
            routes.put("color", colorRoute);
        }
        if (pastedImageVector != null) {
            Map<Long, Double> imageRoute = imageSimilarityRoute(pastedImageVector, snapshots, excluded, true);
            if (!imageRoute.isEmpty()) {
                routes.put("image", imageRoute);
            }
        }
        if (pastedClipVector != null) {
            Map<Long, Double> clipRoute = imageSimilarityRoute(pastedClipVector, snapshots, excluded, false);
            if (!clipRoute.isEmpty()) {
                routes.put("clip", clipRoute);
            }
        }
        if (routes.isEmpty() && !intent.filters().isEmpty()) {
            Map<Long, Double> metaRoute = new HashMap<>();
            for (Asset asset : assets.values()) {
                if (!excluded.contains(asset.getId())) {
                    metaRoute.put(asset.getId(), 1.0);
                }
            }
            if (!metaRoute.isEmpty()) {
                routes.put("meta", metaRoute);
            }
        }

        Map<Long, Double> fused = reciprocalRankFusion(routes);
        Map<String, Object> routeDetail = new LinkedHashMap<>();
        routes.forEach((name, scores) -> routeDetail.put(name, scores.size()));
        progress.accept(SearchProgressEvent.of("retrieve",
                routes.isEmpty() ? "未召回候选"
                        : "召回候选 " + fused.size() + " 张（" + String.join("+", routes.keySet()) + "）",
                Map.of("routes", routeDetail, "candidates", fused.size())));
        // 相似图多跳扩展：取融合头部为种子，把图邻居按跳数衰减并入一路（图未建时为 no-op）
        GraphExpansion expansion = graphRoute(fused, excluded, assets);
        if (!expansion.route().isEmpty()) {
            List<Map<String, Object>> nodes = new ArrayList<>();
            Set<Long> seedIds = new LinkedHashSet<>();
            expansion.traversal().forEach(edge -> seedIds.add(edge.seedId()));
            for (Long id : seedIds) {
                Asset asset = assets.get(id);
                nodes.add(Map.of("id", id, "seed", true,
                        "label", asset == null ? ("#" + id) : asset.getOriginalFilename()));
            }
            Set<Long> neighborIds = new LinkedHashSet<>(expansion.route().keySet());
            for (Long id : neighborIds) {
                Asset asset = assets.get(id);
                nodes.add(Map.of("id", id, "seed", false,
                        "label", asset == null ? ("#" + id) : asset.getOriginalFilename()));
            }
            List<Map<String, Object>> edges = expansion.traversal().stream().limit(240)
                    .map(edge -> Map.<String, Object>of("a", edge.seedId(), "b", edge.neighborId(),
                            "score", Math.round(edge.score() * 1000) / 1000.0))
                    .toList();
            progress.accept(SearchProgressEvent.of("graph",
                    "无向图扩展 " + expansion.route().size() + " 张（种子 " + expansion.seeds() + "）",
                    Map.of("seeds", expansion.seeds(), "neighbors", expansion.route().size(),
                            "nodes", nodes, "edges", edges)));
            routes.put("graph", expansion.route());
            fused = reciprocalRankFusion(routes);
        }
        List<Scored> scored = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : fused.entrySet()) {
            Asset asset = assets.get(entry.getKey());
            if (asset == null) {
                continue;
            }
            AssetEmbeddingCache.Snapshot snapshot = snapshots.get(asset.getId());
            double userSimilarity = 0;
            for (double[] userVector : userVectors.getOrDefault(asset.getId(), List.of())) {
                for (double[] queryVector : queryVectors) {
                    userSimilarity = Math.max(userSimilarity, VectorUtils.cosine(queryVector, userVector));
                }
            }
            double characterHit = queryCharacterId != null && queryCharacterId.equals(asset.getCharacterId())
                    ? CHARACTER_WEIGHT : 0;
            double score = entry.getValue()
                    + USER_SIM_WEIGHT * Math.max(userSimilarity, 0)
                    + AFFINITY_WEIGHT * Math.tanh(affinity.getOrDefault(asset.getId(), 0.0))
                    + characterHit
                    + facetBonus(facetAffinity, snapshot)
                    + wallpaperBonus(queryFacets, snapshot);
            scored.add(new Scored(snapshot, asset, score,
                    documentText(asset, snapshot, characterNames, folderNamesByAsset)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(candidate -> candidate.asset().getId()));
        normalizeScores(scored);

        int candidateCount = Math.min(scored.size(), Math.max(limit * CANDIDATE_MULTIPLIER, limit));
        List<Scored> candidates = new ArrayList<>(scored.subList(0, candidateCount));

        progress.accept(SearchProgressEvent.of("rerank", "重排候选 " + candidates.size() + " 张…"));
        String comment = applyRerank(effectiveQuery, intent, candidates, queryVectors);
        progress.accept(SearchProgressEvent.of("rerank", "重排完成"));
        // 发色矛盾惩罚：查询明确指定发色时，与描述/标签发色冲突的候选降权
        int adjusted = applyHairColorPenalty(effectiveQuery, intent, candidates);
        if (adjusted > 0) {
            progress.accept(SearchProgressEvent.of("adjust", "发色校正 " + adjusted + " 张",
                    Map.of("adjusted", adjusted)));
        }
        candidates.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(candidate -> candidate.asset().getId()));
        // 视觉二次确认：覆盖所有超过置信度阈值的候选（分批调用，无硬性张数上限）；
        // 未被确认但超过阈值的会在结果里带 verified=false 标记
        List<Scored> verifyTargets = selectVerifyTargets(candidates, minConfidence, limit);
        String verifyComment = verifyCandidates(effectiveQuery, intent, queryFacets, verifyTargets, progress);
        if (verifyComment != null) {
            comment = verifyComment;
        }
        candidates.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(candidate -> candidate.asset().getId()));

        List<Scored> filtered = candidates.stream()
                .filter(candidate -> minConfidence == null || candidate.score() >= minConfidence)
                .toList();
        // 近重复抑制：同一强连通组（图边 ≥ duplicateThreshold）只保留得分最高的一张
        Set<Long> suppressed = suppressDuplicates(filtered);
        if (!suppressed.isEmpty()) {
            progress.accept(SearchProgressEvent.of("dedupe", "近重复去重 " + suppressed.size() + " 张",
                    Map.of("suppressed", suppressed.size())));
        }
        List<AgentSearchResult> results = filtered.stream()
                .filter(candidate -> !suppressed.contains(candidate.asset().getId()))
                .limit(limit)
                .map(candidate -> toResult(candidate, characterNames))
                .toList();
        long verifiedCount = results.stream().filter(AgentSearchResult::verified).count();
        progress.accept(SearchProgressEvent.of("done", "完成 · 返回 " + results.size() + " 条结果",
                Map.of("results", results.size(), "verified", verifiedCount,
                        "unverified", results.size() - verifiedCount, "suppressed", suppressed.size())));
        AgentSearchResponse response = new AgentSearchResponse(query, results, comment, aiClient.available(),
                intent.positiveTerms(), intent.colors(), intent.negatives(), intent.filters().describe());
        if (properties.searchCacheTtl() != null && !properties.searchCacheTtl().isZero()
                && !properties.searchCacheTtl().isNegative()) {
            queryCache.put(cacheKey, new CachedResponse(response, Instant.now().plus(properties.searchCacheTtl())));
        }
        return response;
    }

    private String applyRerank(String query, QueryIntent intent, List<Scored> candidates, List<double[]> queryVectors) {
        if (candidates.isEmpty()) {
            return null;
        }
        List<Scored> rerankable = candidates.subList(0, Math.min(candidates.size(), MAX_RERANK_CANDIDATES));
        String comment = null;
        boolean localApplied = false;
        if (modelProperties.rerank() && imageModelClient.available()) {
            LocalRerankResult local = localRerank(query, rerankable);
            if (local.applied()) {
                comment = local.comment();
                localApplied = true;
            }
        }
        // 本地精排成功即收工：两者叠加会白白多花 ~6k tokens 与 20-40s，且分数被二次回混
        if (!localApplied && comment == null && !queryVectors.isEmpty()
                && properties.rerank() && aiClient.chatEnabled()) {
            RerankResult rerank = rerank(query, rerankable);
            if (rerank != null) {
                for (Scored candidate : rerankable) {
                    Double llmConfidence = rerank.confidences().get(candidate.asset().getId());
                    if (llmConfidence != null) {
                        candidate.setScore(clamp01(LLM_WEIGHT * llmConfidence + (1 - LLM_WEIGHT) * candidate.score()));
                    }
                }
                rerankable.sort(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(candidate -> candidate.asset().getId()));
                comment = rerank.comment();
            }
        }
        return comment;
    }

    /**
     * 发色矛盾惩罚：查询含头发指示词且解析出颜色时生效。
     * 候选发色（描述中文词 + WD14 标签）与查询发色完全不符 → ×0.35；含查询发色但另有异色（双色发/多角色）→ ×0.6。
     * 候选无发色信息时不罚（交给视觉确认）。
     */
    private int applyHairColorPenalty(String query, QueryIntent intent, List<Scored> candidates) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (!(lower.contains("发") || lower.contains("毛") || lower.contains("hair")) || intent.colors().isEmpty()) {
            return 0;
        }
        Set<String> compatible = new HashSet<>();
        for (String color : intent.colors()) {
            String family = HAIR_FAMILY_ALIASES.get(color);
            if (family != null) {
                compatible.add(family);
            }
        }
        if (compatible.isEmpty()) {
            return 0;
        }
        int adjusted = 0;
        for (Scored candidate : candidates) {
            Set<String> families = hairFamilies(candidate.snapshot().description(), candidate.snapshot().tags());
            if (families.isEmpty()) {
                continue;
            }
            boolean allCompatible = families.stream().allMatch(compatible::contains);
            if (allCompatible) {
                continue;
            }
            boolean anyCompatible = families.stream().anyMatch(compatible::contains);
            candidate.setScore(candidate.score() * (anyCompatible ? 0.6 : 0.35));
            adjusted++;
        }
        return adjusted;
    }

    private Set<String> hairFamilies(String description, String tagsJson) {
        Set<String> families = new HashSet<>();
        if (description != null) {
            for (Map.Entry<String, List<String>> entry : HAIR_WORD_FAMILIES.entrySet()) {
                for (String word : entry.getValue()) {
                    if (description.contains(word)) {
                        families.add(entry.getKey());
                        break;
                    }
                }
            }
        }
        if (tagsJson != null) {
            for (Map.Entry<String, List<String>> entry : HAIR_TAG_FAMILIES.entrySet()) {
                for (String tag : entry.getValue()) {
                    if (tagsJson.contains("\"" + tag + "\"")) {
                        families.add(entry.getKey());
                        break;
                    }
                }
            }
        }
        return families;
    }

    /**
     * 视觉二次确认对象：超过置信度阈值的全部候选（无阈值时为将返回的前 limit 名）。
     */
    private List<Scored> selectVerifyTargets(List<Scored> candidates, Double minConfidence, int limit) {
        List<Scored> targets = new ArrayList<>();
        for (Scored candidate : candidates) {
            if (minConfidence != null) {
                if (candidate.score() >= minConfidence) {
                    targets.add(candidate);
                }
            } else if (targets.size() < limit) {
                targets.add(candidate);
            }
        }
        return targets;
    }

    /**
     * 分批进行视觉二次确认（每批 {@code lumina.ai.verify-batch-size} 张图片一次调用），覆盖全部目标候选。
     * 批次之间无依赖，按 {@code lumina.ai.verify-batch-concurrency} 并发执行（默认 3），进度按完成图片数上报。
     * 成功的批次给候选打 verified 标记；批次失败保留原分并由结果标记告知用户。
     */
    private String verifyCandidates(String query, QueryIntent intent, Map<String, List<String>> facets,
                                    List<Scored> targets, Consumer<SearchProgressEvent> progress) {
        if (!properties.verify() || !aiClient.visionEnabled() || targets.isEmpty()) {
            return null;
        }
        // 先统一准备小图（缩略图/压缩），按批分组
        int batchSize = Math.max(1, properties.verifyBatchSize());
        List<List<Scored>> batches = new ArrayList<>();
        List<List<VisionImage>> batchImages = new ArrayList<>();
        List<Scored> currentCandidates = new ArrayList<>();
        List<VisionImage> currentImages = new ArrayList<>();
        int prepared = 0;
        for (Scored candidate : targets) {
            VisionImage image = prepareVisionImage(candidate.asset());
            if (image == null) {
                continue;
            }
            currentCandidates.add(candidate);
            currentImages.add(image);
            prepared++;
            if (currentCandidates.size() >= batchSize) {
                batches.add(currentCandidates);
                batchImages.add(currentImages);
                currentCandidates = new ArrayList<>();
                currentImages = new ArrayList<>();
            }
        }
        if (!currentCandidates.isEmpty()) {
            batches.add(currentCandidates);
            batchImages.add(currentImages);
        }
        if (batches.isEmpty()) {
            return null;
        }
        String comment = null;
        int concurrency = Math.max(1, Math.min(properties.verifyBatchConcurrency(), batches.size()));
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "verify-batch");
            thread.setDaemon(true);
            return thread;
        });
        try {
            ExecutorCompletionService<BatchVerifyResult> completion = new ExecutorCompletionService<>(executor);
            for (int i = 0; i < batches.size(); i++) {
                int index = i;
                completion.submit(() -> {
                    try {
                        String batchComment = verifyBatch(query, intent, facets, batches.get(index), batchImages.get(index));
                        return new BatchVerifyResult(index, batchComment, null);
                    } catch (Exception e) {
                        return new BatchVerifyResult(index, null, e);
                    }
                });
            }
            int completedImages = 0;
            int firstCommentIndex = Integer.MAX_VALUE;
            for (int i = 0; i < batches.size(); i++) {
                BatchVerifyResult result = completion.take().get();
                int index = result.index();
                if (result.error() == null) {
                    for (Scored candidate : batches.get(index)) {
                        candidate.setVerified(true);
                    }
                    // 仅保留排名最高一批的总结，避免多批注释拼接成重复碎片
                    if (result.comment() != null && index < firstCommentIndex) {
                        comment = result.comment();
                        firstCommentIndex = index;
                    }
                } else {
                    log.warn("视觉二次确认批次失败，保留当前排序: {}", result.error().getMessage());
                }
                completedImages += batches.get(index).size();
                progress.accept(new SearchProgressEvent("vision",
                        "视觉确认 " + completedImages + "/" + prepared, completedImages, prepared, null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("视觉二次确认并发执行失败: {}", e.getMessage());
        } finally {
            executor.shutdownNow();
        }
        progress.accept(SearchProgressEvent.of("vision", "视觉确认完成"));
        return comment;
    }

    private record BatchVerifyResult(int index, String comment, Exception error) {
    }

    private String verifyBatch(String query, QueryIntent intent, Map<String, List<String>> facets,
                               List<Scored> withImages, List<VisionImage> images) {
        StringBuilder prompt = new StringBuilder("搜索描述：").append(query).append('\n');
        prompt.append("解析意图：关键词=").append(intent.positiveTerms())
                .append("；颜色=").append(intent.colors())
                .append("；筛选=").append(intent.filters().describe())
                .append("；排除=").append(intent.negatives()).append('\n');
        if (facets != null && !facets.isEmpty()) {
            prompt.append("感性特征（判断氛围/风格/色调/用途是否符合时参考）：").append(facets).append('\n');
        }
        prompt.append("候选项（按当前置信度排序，随后按顺序附有 ").append(withImages.size()).append(" 张对应图片）：\n");
        for (int i = 0; i < withImages.size(); i++) {
            Scored candidate = withImages.get(i);
            prompt.append(i + 1).append(". id=").append(candidate.asset().getId())
                    .append(" 当前置信度=").append(Math.round(candidate.score() * 10000) / 10000.0)
                    .append(" 元数据：").append(candidate.documentText()).append('\n');
        }
        String content = aiClient.completeWithImages(VERIFY_SYSTEM_PROMPT, prompt.toString(), images);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("视觉确认无响应");
        }
        JsonNode root = objectMapper.readTree(stripCodeFence(content));
        Map<Long, Double> confidences = new HashMap<>();
        for (JsonNode item : root.path("results")) {
            confidences.put(item.path("id").asLong(), clamp01(item.path("confidence").asDouble(0)));
        }
        for (Scored candidate : withImages) {
            Double verified = confidences.get(candidate.asset().getId());
            if (verified != null) {
                candidate.setScore(clamp01(VERIFY_WEIGHT * verified + (1 - VERIFY_WEIGHT) * candidate.score()));
            }
        }
        String comment = root.path("comment").asString("");
        return comment.isBlank() ? null : comment;
    }

    /**
     * 准备送给视觉确认的小图：优先进口缩略图（≤480px，本身已足够看清）；
     * 无缩略图或文件过大时压到 ≤512px——LLM 只看"能不能看清"，元数据另有提供，控制视觉 token。
     */
    private VisionImage prepareVisionImage(Asset asset) {
        try {
            String key = asset.getThumbnailKey() != null ? asset.getThumbnailKey() : asset.getStorageKey();
            if (key == null) {
                return null;
            }
            byte[] bytes = assetStorage.get(key);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            if (asset.getThumbnailKey() == null || bytes.length > MAX_VERIFY_IMAGE_BYTES) {
                byte[] downscaled = downscale(bytes);
                if (downscaled != null) {
                    return new VisionImage(downscaled, "image/jpeg");
                }
            }
            return new VisionImage(bytes, ImageUtils.detectContentType(bytes, "image/jpeg"));
        } catch (Exception e) {
            log.debug("候选图片读取失败({}): {}", asset.getId(), e.getMessage());
            return null;
        }
    }

    /** 粘贴图交给 LLM 前压到 ≤1024px（本地标签/向量模型仍用原图），避免大图浪费视觉 token。 */
    private VisionImage preparePastedImage(byte[] data, String contentType) {
        byte[] downscaled = ImageUtils.downscaleJpeg(data, PASTED_VISION_MAX_SIDE);
        if (downscaled != null) {
            return new VisionImage(downscaled, "image/jpeg");
        }
        return new VisionImage(data, contentType == null ? "image/jpeg" : contentType);
    }

    private byte[] downscale(byte[] image) {
        return ImageUtils.downscaleJpeg(image, VERIFY_MAX_SIDE);
    }

    private record LocalRerankResult(boolean applied, String comment) {
    }

    private LocalRerankResult localRerank(String query, List<Scored> candidates) {
        try {
            List<String> documents = candidates.stream()
                    .map(Scored::documentText)
                    .toList();
            List<Double> scores = imageModelClient.rerank(query, documents);
            if (scores == null || scores.size() != candidates.size()) {
                return new LocalRerankResult(false, null);
            }
            for (int i = 0; i < candidates.size(); i++) {
                Scored candidate = candidates.get(i);
                double rerankScore = clamp01(scores.get(i));
                candidate.setScore(clamp01(LLM_WEIGHT * rerankScore + (1 - LLM_WEIGHT) * candidate.score()));
            }
            candidates.sort(Comparator.comparingDouble(Scored::score).reversed()
                    .thenComparing(candidate -> candidate.asset().getId()));
            return new LocalRerankResult(true, null);
        } catch (Exception e) {
            log.debug("本地精排失败，回退: {}", e.getMessage());
            return new LocalRerankResult(false, null);
        }
    }

    private void normalizeScores(List<Scored> scored) {
        if (scored.isEmpty()) {
            return;
        }
        double max = scored.get(0).score();
        if (max <= 1.0 || max <= 0) {
            return;
        }
        for (Scored candidate : scored) {
            candidate.setScore(candidate.score() / max);
        }
    }

    private Map<Long, Double> imageSimilarityRoute(double[] queryVector,
                                                   Map<Long, AssetEmbeddingCache.Snapshot> snapshots,
                                                   Set<Long> excluded, boolean useWd14) {
        Map<Long, Double> route = new HashMap<>();
        for (AssetEmbeddingCache.Snapshot snapshot : snapshots.values()) {
            if (excluded.contains(snapshot.assetId())) {
                continue;
            }
            double[] candidate = useWd14 ? snapshot.image() : snapshot.clip();
            if (candidate == null) {
                continue;
            }
            double similarity = (VectorUtils.cosine(queryVector, candidate) + 1) / 2;
            if (similarity > 0) {
                route.put(snapshot.assetId(), similarity);
            }
        }
        return route;
    }

    private String buildEffectiveQuery(String query, String imageDescription, String imageOcr, List<String> imageTags) {
        StringBuilder text = new StringBuilder(query == null ? "" : query);
        if (imageDescription != null && !imageDescription.isBlank()) {
            text.append(' ').append(imageDescription);
        }
        if (imageOcr != null && !imageOcr.isBlank()) {
            text.append(' ').append(imageOcr);
        }
        if (text.isEmpty() && !imageTags.isEmpty()) {
            text.append(String.join(" ", imageTags));
        }
        return text.toString().trim();
    }

    private QueryIntent mergeTerms(QueryIntent intent, List<String> extraTerms) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>(intent.expanded());
        for (String term : extraTerms) {
            if (term != null && !term.isBlank()) {
                expanded.add(term);
            }
        }
        return new QueryIntent(intent.raw(), intent.tokens(), List.copyOf(expanded), intent.negatives(),
                intent.colors(), intent.filters(), intent.characterHint(), intent.rewrite(), intent.facets());
    }

    private Map<Long, Double> vectorRoute(List<double[]> queryVectors,
                                          Map<Long, AssetEmbeddingCache.Snapshot> snapshots,
                                          Set<Long> excluded) {
        Map<Long, Double> route = new HashMap<>();
        for (AssetEmbeddingCache.Snapshot snapshot : snapshots.values()) {
            if (snapshot.text() == null || excluded.contains(snapshot.assetId())) {
                continue;
            }
            double best = -1;
            for (double[] queryVector : queryVectors) {
                best = Math.max(best, (VectorUtils.cosine(queryVector, snapshot.text()) + 1) / 2);
            }
            if (best >= 0) {
                route.put(snapshot.assetId(), best);
            }
        }
        return route;
    }

    private Map<Long, Double> tagRoute(QueryIntent intent,
                                       Map<Long, AssetEmbeddingCache.Snapshot> snapshots,
                                       Set<Long> excluded) {
        List<String> terms = intent.positiveTerms();
        if (terms.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> documentFrequency = new HashMap<>();
        Map<Long, List<String>> tagsByAsset = new HashMap<>();
        for (AssetEmbeddingCache.Snapshot snapshot : snapshots.values()) {
            List<String> tags = VectorUtils.stringsFromJson(snapshot.tags(), objectMapper).stream()
                    .map(tag -> tag.toLowerCase(Locale.ROOT))
                    .toList();
            tagsByAsset.put(snapshot.assetId(), tags);
            for (String tag : new HashSet<>(tags)) {
                documentFrequency.merge(tag, 1, Integer::sum);
            }
        }
        int documents = Math.max(snapshots.size(), 1);
        Map<Long, Double> route = new HashMap<>();
        for (Map.Entry<Long, List<String>> entry : tagsByAsset.entrySet()) {
            if (excluded.contains(entry.getKey())) {
                continue;
            }
            double score = 0;
            for (String term : terms) {
                for (String tag : entry.getValue()) {
                    double idf = Math.log(1.0 + documents / (1.0 + documentFrequency.getOrDefault(tag, 1)));
                    if (tag.equals(term)) {
                        score += idf;
                    } else if (tag.contains(term) || term.contains(tag)) {
                        score += 0.5 * idf;
                    }
                }
            }
            if (score > 0) {
                route.put(entry.getKey(), score);
            }
        }
        return route;
    }

    /**
     * 感性特征路：查询 facet 值（mood/style/tone/scene/usability）与资产感知画像的 IDF 加权匹配。
     * 匹配范围为同一 facet 内（氛围查氛围），精确匹配计全分、包含关系计半分。
     */
    private Map<Long, Double> profileRoute(Map<String, List<String>> facets,
                                           Map<Long, AssetEmbeddingCache.Snapshot> snapshots,
                                           Set<Long> excluded) {
        if (facets == null || facets.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<Long, List<String>>> valuesByFacet = new HashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (AssetEmbeddingCache.Snapshot snapshot : snapshots.values()) {
            PerceptionProfile profile = perceptionAggregator.parse(snapshot.perception());
            perceptionAggregator.valuesByFacet(profile).forEach((facet, values) -> {
                valuesByFacet.computeIfAbsent(facet, key -> new HashMap<>()).put(snapshot.assetId(), values);
                for (String value : new HashSet<>(values)) {
                    documentFrequency.merge(facetKey(facet, value), 1, Integer::sum);
                }
            });
        }
        int documents = Math.max(snapshots.size(), 1);
        Map<Long, Double> route = new HashMap<>();
        for (Map.Entry<String, List<String>> facetEntry : facets.entrySet()) {
            Map<Long, List<String>> assetsWithFacet = valuesByFacet.get(facetEntry.getKey());
            if (assetsWithFacet == null) {
                continue;
            }
            for (Map.Entry<Long, List<String>> assetEntry : assetsWithFacet.entrySet()) {
                if (excluded.contains(assetEntry.getKey())) {
                    continue;
                }
                double score = 0;
                for (String term : facetEntry.getValue()) {
                    String normalizedTerm = normalizeFacetValue(term);
                    for (String value : assetEntry.getValue()) {
                        String normalizedValue = normalizeFacetValue(value);
                        double idf = Math.log(1.0 + documents
                                / (1.0 + documentFrequency.getOrDefault(facetKey(facetEntry.getKey(), normalizedValue), 1)));
                        if (normalizedValue.equals(normalizedTerm)) {
                            score += idf;
                        } else if (normalizedValue.contains(normalizedTerm) || normalizedTerm.contains(normalizedValue)) {
                            score += 0.5 * idf;
                        }
                    }
                }
                if (score > 0) {
                    route.merge(assetEntry.getKey(), score, Double::sum);
                }
            }
        }
        return route;
    }

    /**
     * 用户感性偏好：把反馈（含图传播）资产画像中的 facet 值累计为偏好分（facet:值 → 带符号和）。
     */
    private Map<String, Double> facetAffinityByValue(Map<Long, Double> feedbackSigns,
                                                     Map<Long, AssetEmbeddingCache.Snapshot> snapshots) {
        if (feedbackSigns.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> affinity = new HashMap<>();
        for (Map.Entry<Long, Double> entry : feedbackSigns.entrySet()) {
            AssetEmbeddingCache.Snapshot snapshot = snapshots.get(entry.getKey());
            if (snapshot == null || snapshot.perception() == null) {
                continue;
            }
            PerceptionProfile profile = perceptionAggregator.parse(snapshot.perception());
            perceptionAggregator.valuesByFacet(profile).forEach((facet, values) ->
                    values.forEach(value -> affinity.merge(facetKey(facet, value), entry.getValue(), Double::sum)));
        }
        return affinity;
    }

    /** 候选画像与用户感性偏好的匹配加分（tanh 压缩到 [-1,1] × 权重）。 */
    private double facetBonus(Map<String, Double> facetAffinity, AssetEmbeddingCache.Snapshot snapshot) {
        if (facetAffinity.isEmpty() || snapshot == null || snapshot.perception() == null) {
            return 0;
        }
        PerceptionProfile profile = perceptionAggregator.parse(snapshot.perception());
        double sum = 0;
        for (Map.Entry<String, List<String>> facet : perceptionAggregator.valuesByFacet(profile).entrySet()) {
            for (String value : facet.getValue()) {
                sum += facetAffinity.getOrDefault(facetKey(facet.getKey(), normalizeFacetValue(value)), 0.0);
            }
        }
        return FACET_AFFINITY_WEIGHT * Math.tanh(sum);
    }

    /** 壁纸诉求：查询要壁纸时，按资产壁纸适配分给渐变加分。 */
    private double wallpaperBonus(Map<String, List<String>> queryFacets, AssetEmbeddingCache.Snapshot snapshot) {
        List<String> usability = queryFacets == null ? null : queryFacets.get(PerceptionLexicon.USABILITY);
        if (usability == null || usability.isEmpty() || snapshot == null || snapshot.perception() == null) {
            return 0;
        }
        boolean wantsWallpaper = usability.stream().anyMatch(value -> value.contains("壁纸"));
        if (!wantsWallpaper) {
            return 0;
        }
        return WALLPAPER_BONUS_WEIGHT * perceptionAggregator.parse(snapshot.perception()).wallpaperScore();
    }

    private String facetKey(String facet, String value) {
        return facet + ":" + normalizeFacetValue(value);
    }

    private String normalizeFacetValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<Long, Double> fuzzyRoute(String query,
                                         QueryIntent intent,
                                         Map<Long, Asset> assets,
                                         Map<Long, AssetEmbeddingCache.Snapshot> snapshots,
                                         Set<Long> excluded,
                                         Map<Long, String> characterNames,
                                         Map<Long, List<String>> folderNames) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(query.toLowerCase(Locale.ROOT));
        terms.addAll(intent.positiveTerms());
        if (intent.rewrite() != null && !intent.rewrite().isBlank()) {
            terms.add(intent.rewrite().toLowerCase(Locale.ROOT));
        }
        Map<Long, Double> route = new HashMap<>();
        for (Asset asset : assets.values()) {
            if (excluded.contains(asset.getId())) {
                continue;
            }
            AssetEmbeddingCache.Snapshot snapshot = snapshots.get(asset.getId());
            String text = fullDocumentText(asset, snapshot, characterNames, folderNames).toLowerCase(Locale.ROOT);
            double best = 0;
            for (String term : terms) {
                if (term.isBlank()) {
                    continue;
                }
                if (text.contains(term)) {
                    best = Math.max(best, 0.95);
                } else {
                    best = Math.max(best, diceBigrams(term, text));
                }
            }
            if (best > FUZZY_THRESHOLD) {
                route.put(asset.getId(), best);
            }
        }
        return route;
    }

    private Map<Long, Double> colorRoute(QueryIntent intent,
                                         Map<Long, Asset> assets,
                                         Set<Long> excluded) {
        if (intent.colors().isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> route = new HashMap<>();
        for (Asset asset : assets.values()) {
            if (excluded.contains(asset.getId()) || asset.getColors() == null || asset.getColors().isBlank()) {
                continue;
            }
            Set<String> assetColors = Set.of(asset.getColors().toLowerCase(Locale.ROOT).split(","));
            long matches = intent.colors().stream().filter(assetColors::contains).count();
            if (matches > 0) {
                route.put(asset.getId(), (double) matches / intent.colors().size());
            }
        }
        return route;
    }

    private Set<Long> excludedAssets(Map<Long, Asset> assets,
                                     Map<Long, AssetEmbeddingCache.Snapshot> snapshots,
                                     QueryIntent intent,
                                     Map<Long, String> characterNames,
                                     Map<Long, List<String>> folderNames) {
        boolean hasNegatives = !intent.negatives().isEmpty();
        boolean hasFilters = !intent.filters().isEmpty();
        if (!hasNegatives && !hasFilters) {
            return Set.of();
        }
        Set<Long> excluded = new HashSet<>();
        for (Asset asset : assets.values()) {
            if (hasFilters && !passesFilters(asset, intent.filters())) {
                excluded.add(asset.getId());
                continue;
            }
            if (hasNegatives) {
                String text = fullDocumentText(asset, snapshots.get(asset.getId()), characterNames, folderNames)
                        .toLowerCase(Locale.ROOT);
                for (String negative : intent.negatives()) {
                    if (!negative.isBlank() && text.contains(negative)) {
                        excluded.add(asset.getId());
                        break;
                    }
                }
            }
        }
        return excluded;
    }

    private boolean passesFilters(Asset asset, QueryIntent.SearchFilters filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        Integer width = asset.getWidth();
        Integer height = asset.getHeight();
        if (filters.orientation() != null && width != null && height != null && width > 0 && height > 0) {
            double ratio = (double) width / height;
            switch (filters.orientation()) {
                case "landscape" -> {
                    if (ratio < 1.05) {
                        return false;
                    }
                }
                case "portrait" -> {
                    if (ratio > 0.95) {
                        return false;
                    }
                }
                default -> {
                    if (Math.abs(ratio - 1.0) > 0.05) {
                        return false;
                    }
                }
            }
        }
        if (filters.minLongSide() != null && width != null && height != null
                && Math.max(width, height) < filters.minLongSide()) {
            return false;
        }
        if (filters.author() != null
                && !containsIgnoreCase(asset.getAuthor(), filters.author().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (filters.camera() != null) {
            AssetMetadata metadata = asset.getMetadata();
            String cameraText = metadata == null ? "" : (nullToEmpty(metadata.cameraMake()) + " "
                    + nullToEmpty(metadata.cameraModel()) + " " + nullToEmpty(metadata.lensModel())).trim();
            if (!containsIgnoreCase(cameraText, filters.camera().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        if (filters.dateFrom() != null || filters.dateTo() != null) {
            LocalDate date = assetDate(asset);
            if (date == null) {
                return false;
            }
            try {
                if (filters.dateFrom() != null && date.isBefore(LocalDate.parse(filters.dateFrom()))) {
                    return false;
                }
                if (filters.dateTo() != null && date.isAfter(LocalDate.parse(filters.dateTo()))) {
                    return false;
                }
            } catch (Exception ignored) {
                // 非法日期条件视为不过滤
            }
        }
        return true;
    }

    private LocalDate assetDate(Asset asset) {
        String raw = asset.getMetadata() == null ? null : asset.getMetadata().dateTaken();
        if (raw != null && !raw.isBlank()) {
            try {
                return LocalDate.parse(normalizedDate(raw));
            } catch (Exception ignored) {
                // 回退到入库时间
            }
        }
        return asset.getCreatedAt() == null ? null : asset.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private String normalizedDate(String raw) {
        String value = raw.trim().replace(':', '-').replace('/', '-');
        int space = value.indexOf(' ');
        if (space > 0) {
            value = value.substring(0, space);
        }
        return value.length() >= 10 ? value.substring(0, 10) : value;
    }

    private boolean containsIgnoreCase(String text, String needle) {
        return text != null && needle != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private Map<Long, Double> reciprocalRankFusion(Map<String, Map<Long, Double>> routes) {
        Map<Long, Double> fused = new HashMap<>();
        double max = 0;
        for (Map.Entry<String, Map<Long, Double>> routeEntry : routes.entrySet()) {
            double weight = routeWeight(routeEntry.getKey());
            max += weight;
            List<Map.Entry<Long, Double>> ranked = routeEntry.getValue().entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .toList();
            for (int index = 0; index < ranked.size(); index++) {
                fused.merge(ranked.get(index).getKey(), weight / (RRF_K + index + 1), Double::sum);
            }
        }
        double denominator = max <= 0 ? 1.0 : max / (RRF_K + 1.0);
        Map<Long, Double> normalized = new HashMap<>();
        fused.forEach((assetId, value) -> normalized.put(assetId, value / denominator));
        return normalized;
    }

    private double routeWeight(String route) {
        return switch (route) {
            // 粘贴图片的视觉相似路权重更高（以图搜图是主诉求，文字为辅助）
            case "image", "clip" -> 2.0;
            // 图扩展路为辅助召回，权重低于直接路
            case "graph" -> graphSettingsService.effective().routeWeight();
            // 感性特征路：词表小、噪声低，略高于普通文本路
            case "profile" -> PROFILE_ROUTE_WEIGHT;
            // CLIP 文本-图跨模态路：感性检索的主通道
            case "clip_text" -> CLIP_TEXT_ROUTE_WEIGHT;
            default -> 1.0;
        };
    }

    /**
     * 相似图路：以当前融合结果头部为种子，取图邻居并按 1 跳衰减汇总。
     * 顺便记录"走过"的边（种子→邻居），供前端无向图面板绘制与闪烁动画。
     */
    private GraphExpansion graphRoute(Map<Long, Double> fused, Set<Long> excluded, Map<Long, Asset> assets) {
        var settings = graphSettingsService.effective();
        if (!settings.enabled() || similarityGraphService.isEmpty()) {
            return new GraphExpansion(Map.of(), 0, List.of());
        }
        List<Map.Entry<Long, Double>> seeds = fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(settings.routeSeedLimit())
                .toList();
        Map<Long, Double> route = new HashMap<>();
        Map<Long, TraversalEdge> traversal = new LinkedHashMap<>();
        int rank = 0;
        for (Map.Entry<Long, Double> seed : seeds) {
            rank++;
            double decay = rank <= settings.routeNearRank() ? settings.routeDecayNear()
                    : rank <= settings.routeMidRank() ? settings.routeDecayMid()
                    : settings.routeDecayFar();
            for (AssetSimilarityGraphService.Neighbor neighbor : similarityGraphService.neighborsOf(seed.getKey(), settings.routeNeighbors())) {
                long id = neighbor.assetId();
                if (excluded.contains(id) || !assets.containsKey(id)) {
                    continue;
                }
                route.merge(id, neighbor.score() * decay, Math::max);
                long key = hashPair(seed.getKey(), id);
                TraversalEdge existing = traversal.get(key);
                if (existing == null || neighbor.score() > existing.score()) {
                    traversal.put(key, new TraversalEdge(seed.getKey(), id, neighbor.score()));
                }
            }
        }
        return new GraphExpansion(route, seeds.size(), List.copyOf(traversal.values()));
    }

    private static long hashPair(long left, long right) {
        long a = Math.min(left, right);
        long b = Math.max(left, right);
        return a * 1_000_003L + b;
    }

    private record GraphExpansion(Map<Long, Double> route, int seeds, List<TraversalEdge> traversal) {
    }

    private record TraversalEdge(long seedId, long neighborId, double score) {
    }

    /**
     * 反馈传播：SELECT/REJECT 沿相似图扩散（1 跳、2 跳按系数与边权衰减），
     * 已有直接反馈的资产不叠加；用于把"喜欢这张"泛化到相邻图片。
     */
    private void propagateFeedbackAffinity(Map<Long, Double> affinity, Map<Long, Double> feedbackSigns) {
        var settings = graphSettingsService.effective();
        if (!settings.enabled() || feedbackSigns.isEmpty() || similarityGraphService.isEmpty()) {
            return;
        }
        Map<Long, Double> secondHop = new HashMap<>();
        for (Map.Entry<Long, Double> entry : feedbackSigns.entrySet()) {
            long source = entry.getKey();
            double signed = entry.getValue();
            for (AssetSimilarityGraphService.Neighbor neighbor : similarityGraphService.neighborsOf(source, settings.feedbackNeighborLimit(), settings.feedbackEdgeThreshold())) {
                if (neighbor.assetId() == source || feedbackSigns.containsKey(neighbor.assetId())) {
                    continue;
                }
                affinity.merge(neighbor.assetId(), signed * settings.feedbackOneHopFactor() * neighbor.score(), Double::sum);
                if (settings.feedbackTwoHopFactor() <= 0) {
                    continue;
                }
                for (AssetSimilarityGraphService.Neighbor second : similarityGraphService.neighborsOf(neighbor.assetId(), settings.feedbackSecondHopLimit(), settings.feedbackEdgeThreshold())) {
                    if (second.assetId() == source || feedbackSigns.containsKey(second.assetId())) {
                        continue;
                    }
                    secondHop.merge(second.assetId(),
                            signed * settings.feedbackTwoHopFactor() * neighbor.score() * second.score(), Double::sum);
                }
            }
        }
        secondHop.forEach((assetId, value) -> affinity.merge(assetId, value, Double::sum));
    }

    /**
     * 近重复抑制：以强边（≥ duplicateThreshold）做并查集分组，同组只保留排序最高的。
     */
    private Set<Long> suppressDuplicates(List<Scored> filtered) {
        if (filtered.size() < 2) {
            return Set.of();
        }
        List<Long> ids = filtered.stream().map(candidate -> candidate.asset().getId()).toList();
        List<long[]> pairs = similarityGraphService.strongPairs(ids, graphSettingsService.effective().duplicateThreshold());
        if (pairs.isEmpty()) {
            return Set.of();
        }
        Map<Long, Long> parent = new HashMap<>();
        for (Long id : ids) {
            parent.put(id, id);
        }
        for (long[] pair : pairs) {
            union(parent, pair[0], pair[1]);
        }
        Set<Long> keptRoots = new HashSet<>();
        Set<Long> suppressed = new HashSet<>();
        for (Scored candidate : filtered) {
            long root = find(parent, candidate.asset().getId());
            if (!keptRoots.add(root)) {
                suppressed.add(candidate.asset().getId());
            }
        }
        return suppressed;
    }

    private long find(Map<Long, Long> parent, long id) {
        long current = id;
        while (parent.getOrDefault(current, current) != current) {
            current = parent.getOrDefault(current, current);
        }
        long root = current;
        current = id;
        while (parent.getOrDefault(current, current) != root) {
            long next = parent.getOrDefault(current, current);
            parent.put(current, root);
            current = next;
        }
        return root;
    }

    private void union(Map<Long, Long> parent, long left, long right) {
        long leftRoot = find(parent, left);
        long rightRoot = find(parent, right);
        if (leftRoot != rightRoot) {
            parent.put(Math.max(leftRoot, rightRoot), Math.min(leftRoot, rightRoot));
        }
    }

    private Set<String> tagVocabulary(Map<Long, AssetEmbeddingCache.Snapshot> snapshots) {
        Set<String> vocabulary = new HashSet<>();
        for (AssetEmbeddingCache.Snapshot snapshot : snapshots.values()) {
            for (String tag : VectorUtils.stringsFromJson(snapshot.tags(), objectMapper)) {
                if (!tag.isBlank()) {
                    vocabulary.add(tag.toLowerCase(Locale.ROOT));
                }
            }
        }
        return vocabulary;
    }

    private double diceBigrams(String left, String right) {
        if (left.length() < 2 || right.length() < 2) {
            return 0;
        }
        Set<String> leftPairs = bigrams(left);
        Set<String> rightPairs = bigrams(right);
        if (leftPairs.isEmpty() || rightPairs.isEmpty()) {
            return 0;
        }
        int intersection = 0;
        for (String pair : leftPairs) {
            if (rightPairs.contains(pair)) {
                intersection++;
            }
        }
        return 2.0 * intersection / (leftPairs.size() + rightPairs.size());
    }

    private Set<String> bigrams(String value) {
        Set<String> pairs = new HashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            pairs.add(value.substring(i, i + 2));
        }
        return pairs;
    }

    private String documentText(Asset asset, AssetEmbeddingCache.Snapshot snapshot,
                                Map<Long, String> characterNames, Map<Long, List<String>> folderNames) {
        String text = fullDocumentText(asset, snapshot, characterNames, folderNames);
        return text.length() <= MAX_RERANK_DOC_CHARS ? text : text.substring(0, MAX_RERANK_DOC_CHARS);
    }

    private String fullDocumentText(Asset asset, AssetEmbeddingCache.Snapshot snapshot,
                                    Map<Long, String> characterNames, Map<Long, List<String>> folderNames) {
        StringBuilder builder = new StringBuilder(nullToEmpty(asset.getOriginalFilename()));
        appendIfPresent(builder, asset.getAuthor());
        AssetMetadata metadata = asset.getMetadata();
        if (metadata != null) {
            String camera = (nullToEmpty(metadata.cameraMake()) + " " + nullToEmpty(metadata.cameraModel())).trim();
            appendIfPresent(builder, camera);
            appendIfPresent(builder, metadata.lensModel());
            if (metadata.dateTaken() != null && !metadata.dateTaken().isBlank()) {
                String date = normalizedDate(metadata.dateTaken());
                if (date.length() >= 10) {
                    builder.append(' ').append(date).append(' ').append(date, 0, 4);
                }
            }
        }
        if (asset.getWidth() != null && asset.getHeight() != null && asset.getWidth() > 0 && asset.getHeight() > 0) {
            builder.append(' ').append(asset.getWidth()).append('x').append(asset.getHeight());
            double ratio = (double) asset.getWidth() / asset.getHeight();
            if (ratio > 1.05) {
                builder.append(" landscape 横屏");
            } else if (ratio < 0.95) {
                builder.append(" portrait 竖屏");
            }
        }
        if (folderNames != null) {
            List<String> folderNamesOfAsset = folderNames.get(asset.getId());
            if (folderNamesOfAsset != null) {
                for (String folderName : folderNamesOfAsset) {
                    appendIfPresent(builder, folderName);
                }
            }
        }
        if (asset.getCharacterId() != null && characterNames != null) {
            appendIfPresent(builder, characterNames.get(asset.getCharacterId()));
        }
        if (snapshot != null) {
            if (snapshot.description() != null) {
                builder.append(' ').append(snapshot.description());
            }
            // 感性特征词拼入文档：模糊/精排/找相似都能命中
            if (snapshot.perception() != null) {
                String profileText = perceptionAggregator.parse(snapshot.perception()).describeText();
                if (!profileText.isBlank()) {
                    builder.append(' ').append(profileText);
                }
            }
            List<String> tags = VectorUtils.stringsFromJson(snapshot.tags(), objectMapper);
            if (!tags.isEmpty()) {
                builder.append(' ').append(String.join(" ", tags));
            }
            if (snapshot.ocrText() != null) {
                builder.append(' ').append(snapshot.ocrText());
            }
        }
        if (asset.getColors() != null) {
            builder.append(' ').append(asset.getColors().replace(',', ' '));
        }
        return builder.toString().trim();
    }

    private void appendIfPresent(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(' ').append(value.trim());
        }
    }

    private RerankResult rerank(String query, List<Scored> candidates) {
        try {
            StringBuilder prompt = new StringBuilder("搜索词：").append(query).append("\n候选图片：\n");
            for (int i = 0; i < candidates.size(); i++) {
                Scored candidate = candidates.get(i);
                prompt.append(i)
                        .append(". id=").append(candidate.asset().getId())
                        .append(" 文件名=").append(nullToEmpty(candidate.asset().getOriginalFilename()))
                        .append(" 描述=").append(candidate.snapshot() == null ? "" : nullToEmpty(candidate.snapshot().description()))
                        .append(" 标签=").append(candidate.snapshot() == null ? "" : nullToEmpty(candidate.snapshot().tags()))
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
                snapshot == null ? null : snapshot.description(),
                snapshot == null ? List.of() : VectorUtils.stringsFromJson(snapshot.tags(), objectMapper),
                characterId,
                characterId == null ? null : characterNames.get(characterId),
                scored.verified());
    }

    private List<double[]> embedQueryVectors(String query, QueryIntent intent) {
        if (!aiClient.embeddingEnabled()) {
            return List.of();
        }
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        for (String term : intent.positiveTerms()) {
            if (!term.isBlank()) {
                texts.add(term);
            }
        }
        if (texts.isEmpty() && intent.rewrite() != null && !intent.rewrite().isBlank()) {
            texts.add(intent.rewrite());
        }
        if (texts.isEmpty()) {
            texts.add(query);
        }
        List<double[]> vectors = new ArrayList<>();
        for (String text : texts) {
            if (vectors.size() >= MAX_QUERY_VECTORS) {
                break;
            }
            double[] vector = embedText(text);
            if (vector != null) {
                vectors.add(vector);
            }
        }
        return vectors;
    }

    private double[] embedText(String text) {
        double[] cached = vectorCache.get(text);
        if (cached != null) {
            return cached;
        }
        try {
            double[] vector = VectorUtils.toArray(aiClient.embed(text));
            if (vectorCache.size() > VECTOR_CACHE_MAX) {
                vectorCache.clear();
            }
            vectorCache.put(text, vector);
            return vector;
        } catch (Exception e) {
            log.warn("查询向量化失败（{}），跳过该概念: {}", text, e.getMessage());
            return null;
        }
    }

    /**
     * CLIP 文本向量：优先用 LLM 改写（一句话概括），并按 CLIP 77 token 上限截断；
     * 与文本 embedding 分开缓存（维度空间不同）。
     */
    private double[] clipTextVector(QueryIntent intent, String effectiveQuery) {
        String text = intent.rewrite() != null && !intent.rewrite().isBlank() ? intent.rewrite() : effectiveQuery;
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.trim();
        if (text.length() > MAX_CLIP_TEXT_CHARS) {
            text = text.substring(0, MAX_CLIP_TEXT_CHARS);
        }
        String key = "clip:" + text;
        double[] cached = vectorCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            List<Double> embedding = imageModelClient.clipTextEmbed(text);
            if (embedding == null || embedding.isEmpty()) {
                return null;
            }
            double[] vector = VectorUtils.toArray(embedding);
            if (vectorCache.size() > VECTOR_CACHE_MAX) {
                vectorCache.clear();
            }
            vectorCache.put(key, vector);
            return vector;
        } catch (Exception e) {
            log.warn("CLIP 文本向量化失败（{}）: {}", text, e.getMessage());
            return null;
        }
    }

    private Map<Long, Asset> activeAssets() {
        return assetMapper.selectList(Wrappers.<Asset>lambdaQuery().eq(Asset::getDeleted, false))
                .stream()
                .collect(Collectors.toMap(Asset::getId, asset -> asset, (left, right) -> left));
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
        private final String documentText;
        private double score;
        private boolean verified;

        Scored(AssetEmbeddingCache.Snapshot snapshot, Asset asset, double score, String documentText) {
            this.snapshot = snapshot;
            this.asset = asset;
            this.score = score;
            this.documentText = documentText;
        }

        AssetEmbeddingCache.Snapshot snapshot() {
            return snapshot;
        }

        Asset asset() {
            return asset;
        }

        String documentText() {
            return documentText;
        }

        double score() {
            return score;
        }

        void setScore(double score) {
            this.score = score;
        }

        boolean verified() {
            return verified;
        }

        void setVerified(boolean verified) {
            this.verified = verified;
        }
    }

    private record RerankResult(Map<Long, Double> confidences, String comment) {
    }
}
