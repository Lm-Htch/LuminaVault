package com.lumi.vault.business.character.service;

import lombok.extern.slf4j.Slf4j;


import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import com.lumi.vault.business.character.config.CharacterProperties;
import com.lumi.vault.business.character.entity.Character;
import com.lumi.vault.business.character.entity.CharacterReference;
import com.lumi.vault.business.character.mapper.CharacterMapper;
import com.lumi.vault.business.character.mapper.CharacterReferenceMapper;
import com.lumi.vault.business.vector.dto.CharacterVectorMatch;
import com.lumi.vault.business.vector.mapper.VectorSearchMapper;
import com.lumi.vault.config.VectorProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

@Service
@Slf4j
public class CharacterMatchService {
    private static final int ANN_LIMIT = 5;

    private final CharacterMapper characterMapper;
    private final CharacterReferenceMapper referenceMapper;
    private final AssetEmbeddingMapper embeddingMapper;
    private final CharacterProperties properties;
    private final VectorProperties vectorProperties;
    private final VectorSearchMapper vectorSearchMapper;
    private final ObjectMapper objectMapper;

    public CharacterMatchService(CharacterMapper characterMapper,
                                 CharacterReferenceMapper referenceMapper,
                                 AssetEmbeddingMapper embeddingMapper,
                                 CharacterProperties properties,
                                 VectorProperties vectorProperties,
                                 VectorSearchMapper vectorSearchMapper,
                                 ObjectMapper objectMapper) {
        this.characterMapper = characterMapper;
        this.referenceMapper = referenceMapper;
        this.embeddingMapper = embeddingMapper;
        this.properties = properties;
        this.vectorProperties = vectorProperties;
        this.vectorSearchMapper = vectorSearchMapper;
        this.objectMapper = objectMapper;
    }

    public record MatchResult(Long characterId, String characterName, double confidence) {
    }

    public MatchResult match(Long assetId) {
        if (!properties.autoMatch()) {
            return null;
        }
        AssetEmbedding embedding = embeddingMapper.selectById(assetId);
        if (embedding == null) {
            return null;
        }
        if (embedding.getImageEmbedding() != null && embedding.getImageModel() != null) {
            String imageModel = embedding.getImageModel();
            if (vectorProperties.enabled() && vectorProperties.imageModel().equals(imageModel)) {
                MatchResult annMatch = annMatch(embedding.getImageEmbedding());
                if (annMatch != null) {
                    return annMatch;
                }
            }
            MatchResult scanMatch = bestMatch(embedding.getImageEmbedding(),
                    reference -> imageModel.equals(reference.getModel()));
            if (scanMatch != null) {
                return scanMatch;
            }
        }
        if (embedding.getEmbedding() != null) {
            return bestMatch(embedding.getEmbedding(),
                    reference -> reference.getModel() == null || reference.getModel().equals(embedding.getModel()));
        }
        return null;
    }

    private MatchResult annMatch(String embeddingJson) {
        try {
            List<CharacterVectorMatch> matches = vectorSearchMapper.topCharacters(embeddingJson, ANN_LIMIT);
            if (matches.isEmpty()) {
                return null;
            }
            CharacterVectorMatch top = matches.get(0);
            double topSimilarity = top.similarity() == null ? 0 : top.similarity();
            if (topSimilarity < properties.matchThreshold()) {
                return null;
            }
            if (matches.size() > 1) {
                double second = matches.get(1).similarity() == null ? 0 : matches.get(1).similarity();
                if (topSimilarity - second < properties.matchMargin()) {
                    return null;
                }
            }
            Character character = characterMapper.selectById(top.characterId());
            if (character == null) {
                return null;
            }
            return new MatchResult(character.getId(), character.getName(), topSimilarity);
        } catch (Exception e) {
            log.warn("pgvector 匹配失败，回退全量扫描: {}", e.getMessage());
            return null;
        }
    }

    private MatchResult bestMatch(String embeddingJson, Predicate<CharacterReference> filter) {
        double[] vector = VectorUtils.fromJson(embeddingJson, objectMapper);
        if (vector == null) {
            return null;
        }
        Map<Long, Double> best = new HashMap<>();
        for (CharacterReference reference : referenceMapper.selectList(null)) {
            if (!filter.test(reference)) {
                continue;
            }
            double[] referenceVector = VectorUtils.fromJson(reference.getEmbedding(), objectMapper);
            if (referenceVector == null) {
                continue;
            }
            best.merge(reference.getCharacterId(), VectorUtils.cosine(vector, referenceVector), Math::max);
        }
        if (best.isEmpty()) {
            return null;
        }
        List<Map.Entry<Long, Double>> ranked = best.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()))
                .toList();
        Map.Entry<Long, Double> top = ranked.get(0);
        if (top.getValue() < properties.matchThreshold()) {
            return null;
        }
        if (ranked.size() > 1 && top.getValue() - ranked.get(1).getValue() < properties.matchMargin()) {
            return null;
        }
        Character character = characterMapper.selectById(top.getKey());
        if (character == null) {
            return null;
        }
        return new MatchResult(character.getId(), character.getName(), top.getValue());
    }
}
