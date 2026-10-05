package com.lumi.vault.business.character.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.event.RecommendationInvalidatedEvent;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.character.config.CharacterProperties;
import com.lumi.vault.business.character.dto.CharacterRequest;
import com.lumi.vault.business.character.dto.CharacterResponse;
import com.lumi.vault.business.character.entity.Character;
import com.lumi.vault.business.character.entity.CharacterReference;
import com.lumi.vault.business.character.mapper.CharacterMapper;
import com.lumi.vault.business.character.mapper.CharacterReferenceMapper;
import com.lumi.vault.business.vector.mapper.VectorSearchMapper;
import com.lumi.vault.config.VectorProperties;
import com.lumi.vault.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class CharacterService {

    private static final Logger log = LoggerFactory.getLogger(CharacterService.class);

    private final CharacterMapper characterMapper;
    private final CharacterReferenceMapper referenceMapper;
    private final AssetMapper assetMapper;
    private final AssetEmbeddingMapper embeddingMapper;
    private final CharacterProperties properties;
    private final CharacterSettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final VectorProperties vectorProperties;
    private final VectorSearchMapper vectorSearchMapper;

    public CharacterService(CharacterMapper characterMapper,
                            CharacterReferenceMapper referenceMapper,
                            AssetMapper assetMapper,
                            AssetEmbeddingMapper embeddingMapper,
                            CharacterProperties properties,
                            CharacterSettingsService settingsService,
                            ObjectMapper objectMapper,
                            ApplicationEventPublisher eventPublisher,
                            VectorProperties vectorProperties,
                            VectorSearchMapper vectorSearchMapper) {
        this.characterMapper = characterMapper;
        this.referenceMapper = referenceMapper;
        this.assetMapper = assetMapper;
        this.embeddingMapper = embeddingMapper;
        this.properties = properties;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.vectorProperties = vectorProperties;
        this.vectorSearchMapper = vectorSearchMapper;
    }

    private record ReferenceVector(String embedding, String model) {
    }

    public List<CharacterResponse> list() {
        Map<Long, Long> counts = new HashMap<>();
        for (CharacterReference reference : referenceMapper.selectList(null)) {
            counts.merge(reference.getCharacterId(), 1L, Long::sum);
        }
        return characterMapper.selectList(Wrappers.<Character>lambdaQuery().orderByAsc(Character::getName))
                .stream()
                .map(character -> CharacterResponse.of(character, parseAliases(character.getAliases()),
                        counts.getOrDefault(character.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CharacterResponse create(CharacterRequest request) {
        String name = request.name().trim();
        if (characterMapper.selectCount(Wrappers.<Character>lambdaQuery().eq(Character::getName, name)) > 0) {
            throw ApiException.conflict("角色已存在");
        }
        Character character = new Character();
        character.setName(name);
        character.setAliases(toJson(request.aliases()));
        character.setSeries(request.series());
        character.setDescription(request.description());
        characterMapper.insert(character);
        if (request.referenceAssetId() != null) {
            addReference(character.getId(), request.referenceAssetId());
        }
        eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
        return toResponse(characterMapper.selectById(character.getId()));
    }

    @Transactional
    public CharacterResponse update(Long id, CharacterRequest request) {
        Character character = requireCharacter(id);
        String name = request.name().trim();
        if (!name.equals(character.getName())
                && characterMapper.selectCount(Wrappers.<Character>lambdaQuery()
                .eq(Character::getName, name)
                .ne(Character::getId, id)) > 0) {
            throw ApiException.conflict("角色已存在");
        }
        character.setName(name);
        character.setAliases(toJson(request.aliases()));
        character.setSeries(request.series());
        character.setDescription(request.description());
        characterMapper.updateById(character);
        eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
        return toResponse(characterMapper.selectById(id));
    }

    @Transactional
    public void delete(Long id) {
        requireCharacter(id);
        referenceMapper.delete(Wrappers.<CharacterReference>lambdaQuery().eq(CharacterReference::getCharacterId, id));
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getCharacterId, id)
                .set(Asset::getCharacterId, null)
                .set(Asset::getCharacterConfidence, null));
        characterMapper.deleteById(id);
        eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
    }

    @Transactional
    public void addReference(Long characterId, Long assetId) {
        requireCharacter(characterId);
        Asset asset = requireActiveAsset(assetId);
        ReferenceVector vector = referenceVector(assetId);
        if (vector == null) {
            throw ApiException.badRequest("该图片暂无可用向量，无法作为角色参考；请先完成 AI 分析");
        }
        upsertReference(characterId, assetId, vector.embedding(), vector.model(),
                CharacterReference.SOURCE_MANUAL, 1.0);
        if (asset.getCharacterId() == null) {
            assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                    .eq(Asset::getId, assetId)
                    .set(Asset::getCharacterId, characterId)
                    .set(Asset::getCharacterConfidence, 1.0));
        }
    }

    @Transactional
    public void markAsset(Long assetId, Long characterId) {
        requireCharacter(characterId);
        requireActiveAsset(assetId);
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, assetId)
                .set(Asset::getCharacterId, characterId)
                .set(Asset::getCharacterConfidence, 1.0));
        ReferenceVector vector = referenceVector(assetId);
        if (vector != null) {
            upsertReference(characterId, assetId, vector.embedding(), vector.model(),
                    CharacterReference.SOURCE_MANUAL, 1.0);
        }
    }

    @Transactional
    public int markBatch(List<Long> assetIds, Long characterId) {
        if (assetIds == null || assetIds.isEmpty()) {
            return 0;
        }
        if (assetIds.size() > 200) {
            throw ApiException.badRequest("单次最多批量操作 200 张");
        }
        requireCharacter(characterId);
        int marked = assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .in(Asset::getId, assetIds)
                .eq(Asset::getDeleted, false)
                .set(Asset::getCharacterId, characterId)
                .set(Asset::getCharacterConfidence, 1.0));
        for (Long id : assetIds) {
            ReferenceVector vector = referenceVector(id);
            if (vector != null) {
                upsertReference(characterId, id, vector.embedding(), vector.model(),
                        CharacterReference.SOURCE_MANUAL, 1.0);
            }
        }
        eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
        return marked;
    }

    @Transactional
    public void unmarkAsset(Long assetId) {
        requireActiveAsset(assetId);
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, assetId)
                .set(Asset::getCharacterId, null)
                .set(Asset::getCharacterConfidence, null));
        eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
    }

    @Transactional
    public void accumulateReference(Asset asset, String query, String embeddingJson, String model) {
        if (embeddingJson == null || asset == null) {
            return;
        }
        Character character = resolveByQuery(query);
        if (character == null) {
            return;
        }
        upsertReference(character.getId(), asset.getId(), embeddingJson, model,
                CharacterReference.SOURCE_FEEDBACK, properties.feedbackReferenceWeight());
        if (asset.getCharacterId() == null) {
            assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                    .eq(Asset::getId, asset.getId())
                    .set(Asset::getCharacterId, character.getId())
                    .set(Asset::getCharacterConfidence, null));
        }
    }

    public Character matchByTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        for (String tag : tags) {
            String full = normalize(tag);
            if (full.isEmpty()) {
                continue;
            }
            String base = stripParenthetical(full);
            for (Character character : characterMapper.selectList(null)) {
                if (matchesCharacters(character, full) || (base.length() >= 2 && matchesCharacters(character, base))) {
                    return character;
                }
            }
        }
        return null;
    }

    @Transactional
    public Character findOrCreateByTags(List<String> tags) {
        Character matched = matchByTags(tags);
        if (matched != null || !settingsService.autoCreate()) {
            return matched;
        }
        for (String tag : tags) {
            Character created = createAutoCharacter(tag);
            if (created != null) {
                eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
                return created;
            }
        }
        return null;
    }

    @Transactional
    public int bootstrapFromTags() {
        Set<String> tags = new LinkedHashSet<>();
        for (AssetEmbedding embedding : embeddingMapper.selectList(null)) {
            if (embedding.getCharacterTags() == null || embedding.getCharacterTags().isBlank()) {
                continue;
            }
            tags.addAll(VectorUtils.stringsFromJson(embedding.getCharacterTags(), objectMapper));
        }
        int created = 0;
        for (String tag : tags) {
            if (matchByTags(List.of(tag)) != null) {
                continue;
            }
            if (createAutoCharacter(tag) != null) {
                created++;
            }
        }
        if (created > 0) {
            eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
        }
        return created;
    }

    private Character createAutoCharacter(String tag) {
        String full = normalize(tag);
        if (full.isEmpty()) {
            return null;
        }
        String base = stripParenthetical(full);
        if (base.isBlank()) {
            return null;
        }
        String name = titleCase(base);
        if (characterMapper.selectCount(Wrappers.<Character>lambdaQuery().eq(Character::getName, name)) > 0) {
            return null;
        }
        String series = null;
        int open = full.indexOf('(');
        int close = full.lastIndexOf(')');
        if (open > 0 && close > open) {
            series = titleCase(full.substring(open + 1, close));
        }
        Character character = new Character();
        character.setName(name);
        character.setAliases(toJson(List.of(tag, base)));
        character.setSeries(series);
        character.setAutoCreated(true);
        characterMapper.insert(character);
        return character;
    }

    private String titleCase(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (String word : value.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(java.lang.Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                builder.append(word.substring(1));
            }
        }
        return builder.toString();
    }

    public Character resolveByQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        for (Character character : characterMapper.selectList(null)) {
            if (contains(needle, character.getName())) {
                return character;
            }
            for (String alias : parseAliases(character.getAliases())) {
                if (contains(needle, alias)) {
                    return character;
                }
            }
        }
        return null;
    }

    public Map<Long, String> nameMap() {
        Map<Long, String> names = new HashMap<>();
        for (Character character : characterMapper.selectList(null)) {
            names.put(character.getId(), character.getName());
        }
        return names;
    }

    public List<String> parseAliases(String json) {
        return VectorUtils.stringsFromJson(json, objectMapper);
    }

    private ReferenceVector referenceVector(Long assetId) {
        AssetEmbedding embedding = embeddingMapper.selectById(assetId);
        if (embedding == null) {
            return null;
        }
        if (embedding.getImageEmbedding() != null && embedding.getImageModel() != null) {
            return new ReferenceVector(embedding.getImageEmbedding(), embedding.getImageModel());
        }
        if (embedding.getEmbedding() != null) {
            return new ReferenceVector(embedding.getEmbedding(), embedding.getModel());
        }
        return null;
    }

    private void upsertReference(Long characterId, Long assetId, String embeddingJson, String model,
                                 String source, double weight) {
        CharacterReference existing = referenceMapper.selectOne(Wrappers.<CharacterReference>lambdaQuery()
                .eq(CharacterReference::getCharacterId, characterId)
                .eq(CharacterReference::getAssetId, assetId)
                .eq(model != null, CharacterReference::getModel, model)
                .isNull(model == null, CharacterReference::getModel)
                .last("limit 1"));
        if (existing != null) {
            double current = existing.getWeight() == null ? 0 : existing.getWeight();
            if (weight > current) {
                existing.setWeight(weight);
                referenceMapper.updateById(existing);
            }
            return;
        }
        CharacterReference reference = new CharacterReference();
        reference.setCharacterId(characterId);
        reference.setAssetId(assetId);
        reference.setEmbedding(embeddingJson);
        reference.setModel(model);
        reference.setSource(source);
        reference.setWeight(weight);
        referenceMapper.insert(reference);
        syncReferenceVector(reference);
        eventPublisher.publishEvent(new RecommendationInvalidatedEvent());
    }

    private void syncReferenceVector(CharacterReference reference) {
        if (!vectorProperties.enabled() || !vectorProperties.imageModel().equals(reference.getModel())) {
            return;
        }
        try {
            vectorSearchMapper.updateReferenceVector(reference.getId(), reference.getEmbedding());
        } catch (Exception e) {
            log.warn("写入参考向量失败: {}", e.getMessage());
        }
    }

    private CharacterResponse toResponse(Character character) {
        long count = referenceMapper.selectCount(Wrappers.<CharacterReference>lambdaQuery()
                .eq(CharacterReference::getCharacterId, character.getId()));
        return CharacterResponse.of(character, parseAliases(character.getAliases()), count);
    }

    private Character requireCharacter(Long id) {
        Character character = characterMapper.selectById(id);
        if (character == null) {
            throw ApiException.notFound("角色不存在");
        }
        return character;
    }

    private Asset requireActiveAsset(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
            throw ApiException.notFound("资产不存在");
        }
        return asset;
    }

    private boolean contains(String needle, String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() < 2) {
            return false;
        }
        return needle.contains(candidate.toLowerCase(Locale.ROOT));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
    }

    private String stripParenthetical(String value) {
        int parenthesis = value.indexOf('(');
        return parenthesis > 0 ? value.substring(0, parenthesis).trim() : value;
    }

    private boolean matchesCharacters(Character character, String normalized) {
        if (normalized.equals(normalize(character.getName()))) {
            return true;
        }
        for (String alias : parseAliases(character.getAliases())) {
            if (normalized.equals(normalize(alias))) {
                return true;
            }
        }
        return false;
    }

    private String toJson(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        List<String> cleaned = aliases.stream()
                .filter(alias -> alias != null && !alias.isBlank())
                .map(String::trim)
                .toList();
        return cleaned.isEmpty() ? null : VectorUtils.toJson(cleaned, objectMapper);
    }
}
