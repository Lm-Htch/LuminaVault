package com.lumi.vault.business.folder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.client.AiClient;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.folder.dto.FolderRequest;
import com.lumi.vault.business.folder.dto.FolderResponse;
import com.lumi.vault.business.folder.entity.AssetFolder;
import com.lumi.vault.business.folder.entity.Folder;
import com.lumi.vault.business.folder.mapper.AssetFolderMapper;
import com.lumi.vault.business.folder.mapper.FolderMapper;
import com.lumi.vault.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FolderService {

    private static final String CLASSIFY_SYSTEM_PROMPT = """
            你是图片管理助手。请把图片归入已有文件夹；只有当所有已有文件夹都明显不适配时，才建议新建。
            只输出 JSON，不要输出其他内容：
            {"folderId": 已有文件夹id 或 null, "newFolderName": "新文件夹名或 null"}
            规则：
            1. 必须优先复用已有文件夹：只要某个已有文件夹的主题与图片相关、接近、或属于其更细/更粗的一类（如「星空」与「星空夜景」「夜晚星空」互为同一主题），就选择它的 id；
            2. 拿不准时也选择最接近的已有文件夹，而不是新建；
            3. 仅当图片确实与所有已有文件夹主题明显不同，才给出 2-6 个字、尽量宽泛的分类名（如 风景、人物、动物、UI素材），并保证不与任何已有文件夹近义或重复；
            4. 无法判断分类时两者都填 null。""";

    /** 新文件夹名与已有文件夹的近义判定阈值（2-gram Dice）。 */
    private static final double SIMILAR_FOLDER_NAME_THRESHOLD = 0.5;

    private final FolderMapper folderMapper;
    private final AssetFolderMapper assetFolderMapper;
    private final AssetMapper assetMapper;
    private final AssetEmbeddingMapper embeddingMapper;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public FolderService(FolderMapper folderMapper,
                         AssetFolderMapper assetFolderMapper,
                         AssetMapper assetMapper,
                         AssetEmbeddingMapper embeddingMapper,
                         AiClient aiClient,
                         ObjectMapper objectMapper) {
        this.folderMapper = folderMapper;
        this.assetFolderMapper = assetFolderMapper;
        this.assetMapper = assetMapper;
        this.embeddingMapper = embeddingMapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    public List<FolderResponse> list() {
        Map<Long, Long> counts = assetFolderMapper.counts().stream()
                .collect(Collectors.toMap(AssetFolderMapper.FolderCount::folderId,
                        AssetFolderMapper.FolderCount::total, (left, right) -> left));
        return folderMapper.selectList(Wrappers.<Folder>lambdaQuery().orderByAsc(Folder::getName))
                .stream()
                .map(folder -> FolderResponse.of(folder, counts.getOrDefault(folder.getId(), 0L)))
                .toList();
    }

    public Map<Long, String> nameMap() {
        return folderMapper.selectList(null).stream()
                .collect(Collectors.toMap(Folder::getId, Folder::getName, (left, right) -> left));
    }

    public Map<Long, List<String>> folderNamesByAsset() {
        return assetFolderMapper.names().stream()
                .collect(Collectors.groupingBy(AssetFolderMapper.AssetFolderName::assetId,
                        Collectors.mapping(AssetFolderMapper.AssetFolderName::name, Collectors.toList())));
    }

    public List<Long> folderIdsOf(long assetId) {
        return assetFolderMapper.folderIdsOf(assetId);
    }

    public Map<Long, List<Long>> folderIdsByAssets(Collection<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of();
        }
        return assetFolderMapper.selectList(Wrappers.<AssetFolder>lambdaQuery()
                        .in(AssetFolder::getAssetId, assetIds))
                .stream()
                .collect(Collectors.groupingBy(AssetFolder::getAssetId,
                        Collectors.mapping(AssetFolder::getFolderId, Collectors.toList())));
    }

    @Transactional
    public FolderResponse create(FolderRequest request) {
        String name = request.name().trim();
        if (folderMapper.selectCount(Wrappers.<Folder>lambdaQuery().eq(Folder::getName, name)) > 0) {
            throw ApiException.conflict("文件夹已存在");
        }
        Folder folder = new Folder();
        folder.setName(name);
        folder.setDescription(request.description());
        folder.setAutoCreated(false);
        folderMapper.insert(folder);
        return FolderResponse.of(folderMapper.selectById(folder.getId()), 0);
    }

    @Transactional
    public FolderResponse update(Long id, FolderRequest request) {
        Folder folder = requireFolder(id);
        String name = request.name().trim();
        if (!name.equals(folder.getName())
                && folderMapper.selectCount(Wrappers.<Folder>lambdaQuery()
                .eq(Folder::getName, name)
                .ne(Folder::getId, id)) > 0) {
            throw ApiException.conflict("文件夹已存在");
        }
        folder.setName(name);
        folder.setDescription(request.description());
        folderMapper.updateById(folder);
        return FolderResponse.of(folderMapper.selectById(id), activeCount(id));
    }

    @Transactional
    public void delete(Long id) {
        requireFolder(id);
        folderMapper.deleteById(id);
    }

    /**
     * 上传/添加归属：把资产加入指定文件夹（可多归属，已存在则忽略）。
     */
    @Transactional
    public void attach(Long assetId, Long folderId) {
        if (folderId == null) {
            return;
        }
        requireFolder(folderId);
        insertIfAbsent(assetId, folderId);
    }

    /**
     * 单资产移动：清空原有归属并设置到目标文件夹（null 表示移出全部文件夹）。
     */
    @Transactional
    public void assign(Long assetId, Long folderId) {
        requireActiveAsset(assetId);
        if (folderId != null) {
            requireFolder(folderId);
        }
        replaceMembership(assetId, folderId);
    }

    @Transactional
    public void classify(Long assetId) {
        Asset asset = requireActiveAsset(assetId);
        AssetEmbedding embedding = embeddingMapper.selectById(assetId);
        String description = embedding == null ? null : embedding.getDescription();
        List<String> tags = embedding == null ? List.of()
                : VectorUtils.stringsFromJson(embedding.getTags(), objectMapper);
        if (!doClassify(asset, description, tags)) {
            throw ApiException.badRequest("AI 未启用或暂时无法分类，请稍后重试");
        }
    }

    public void classifyIfUnassigned(Asset asset, String description, List<String> tags) {
        if (!assetFolderMapper.folderIdsOf(asset.getId()).isEmpty()) {
            return;
        }
        doClassify(asset, description, tags);
    }

    /**
     * 批量移动：替换所选资产的归属（folderId=null 表示移出全部文件夹）。
     */
    @Transactional
    public int assignBatch(List<Long> assetIds, Long folderId) {
        List<Long> activeIds = activeAssetIds(assetIds);
        if (activeIds.isEmpty()) {
            return 0;
        }
        if (folderId != null) {
            requireFolder(folderId);
        }
        assetFolderMapper.delete(Wrappers.<AssetFolder>lambdaQuery().in(AssetFolder::getAssetId, activeIds));
        if (folderId != null) {
            for (Long id : activeIds) {
                insertIfAbsent(id, folderId);
            }
        }
        return activeIds.size();
    }

    /**
     * 批量添加归属（保留原有文件夹，实现多归属）。
     */
    @Transactional
    public int addBatch(List<Long> assetIds, Long folderId) {
        if (folderId == null) {
            throw ApiException.badRequest("folderId 不能为空");
        }
        requireFolder(folderId);
        List<Long> activeIds = activeAssetIds(assetIds);
        int added = 0;
        for (Long id : activeIds) {
            if (insertIfAbsent(id, folderId)) {
                added++;
            }
        }
        return added;
    }

    /**
     * 批量移出指定文件夹（不影响其它归属）。
     */
    @Transactional
    public int removeBatch(List<Long> assetIds, Long folderId) {
        if (folderId == null) {
            throw ApiException.badRequest("folderId 不能为空");
        }
        requireFolder(folderId);
        List<Long> activeIds = activeAssetIds(assetIds);
        if (activeIds.isEmpty()) {
            return 0;
        }
        return assetFolderMapper.delete(Wrappers.<AssetFolder>lambdaQuery()
                .in(AssetFolder::getAssetId, activeIds)
                .eq(AssetFolder::getFolderId, folderId));
    }

    @Transactional
    public int classifyBatch(List<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return 0;
        }
        if (assetIds.size() > 100) {
            throw ApiException.badRequest("批量分类单次最多 100 张");
        }
        int classified = 0;
        for (Long id : assetIds) {
            Asset asset = assetMapper.selectById(id);
            if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
                continue;
            }
            AssetEmbedding embedding = embeddingMapper.selectById(id);
            String description = embedding == null ? null : embedding.getDescription();
            List<String> tags = embedding == null ? List.of()
                    : VectorUtils.stringsFromJson(embedding.getTags(), objectMapper);
            if (doClassify(asset, description, tags)) {
                classified++;
            }
        }
        return classified;
    }

    public Folder requireFolder(Long id) {
        Folder folder = folderMapper.selectById(id);
        if (folder == null) {
            throw ApiException.notFound("文件夹不存在");
        }
        return folder;
    }

    /**
     * 合并文件夹：把源文件夹的全部图片并入目标文件夹（保留原有归属），随后删除源文件夹。
     * 返回迁移的图片数。
     */
    @Transactional
    public int merge(Long sourceId, Long targetId) {
        if (targetId == null) {
            throw ApiException.badRequest("目标文件夹不能为空");
        }
        if (sourceId.equals(targetId)) {
            throw ApiException.badRequest("不能合并到自身");
        }
        requireFolder(sourceId);
        Folder target = requireFolder(targetId);
        List<AssetFolder> memberships = assetFolderMapper.selectList(Wrappers.<AssetFolder>lambdaQuery()
                .eq(AssetFolder::getFolderId, sourceId));
        for (AssetFolder membership : memberships) {
            insertIfAbsent(membership.getAssetId(), target.getId());
        }
        assetFolderMapper.delete(Wrappers.<AssetFolder>lambdaQuery().eq(AssetFolder::getFolderId, sourceId));
        folderMapper.deleteById(sourceId);
        return memberships.size();
    }

    private boolean doClassify(Asset asset, String description, List<String> tags) {
        if (!aiClient.chatEnabled()) {
            return false;
        }
        boolean hasDescription = description != null && !description.isBlank();
        boolean hasTags = tags != null && !tags.isEmpty();
        if (!hasDescription && !hasTags) {
            return false;
        }
        List<Folder> folders = folderMapper.selectList(Wrappers.<Folder>lambdaQuery().orderByAsc(Folder::getName));
        StringBuilder prompt = new StringBuilder();
        prompt.append("图片描述：").append(hasDescription ? description : "（无）").append('\n');
        prompt.append("图片标签：").append(hasTags ? String.join(", ", tags) : "（无）").append('\n');
        prompt.append("已有文件夹：\n");
        if (folders.isEmpty()) {
            prompt.append("（无）\n");
        }
        for (Folder folder : folders) {
            prompt.append("- ").append(folder.getId()).append(": ").append(folder.getName());
            if (folder.getDescription() != null && !folder.getDescription().isBlank()) {
                prompt.append(" —— ").append(folder.getDescription());
            }
            prompt.append('\n');
        }

        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(aiClient.complete(CLASSIFY_SYSTEM_PROMPT, prompt.toString())));
            if (root.path("folderId").isNumber()) {
                Long folderId = root.path("folderId").asLong();
                if (folderMapper.selectById(folderId) != null) {
                    replaceMembership(asset.getId(), folderId);
                    return true;
                }
            }
            String newName = root.path("newFolderName").asString("").trim();
            if (!newName.isBlank()) {
                Folder existing = folderMapper.selectOne(Wrappers.<Folder>lambdaQuery()
                        .eq(Folder::getName, newName)
                        .last("limit 1"));
                if (existing == null) {
                    // 守卫：与已有文件夹近义/包含时不新建，直接复用最接近的
                    existing = findSimilarFolder(newName, folders);
                }
                Long targetId = existing != null ? existing.getId()
                        : createAutoFolder(newName, hasDescription ? description : null);
                if (targetId == null) {
                    return false;
                }
                replaceMembership(asset.getId(), targetId);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * 找与拟新建名称最接近的已有文件夹（包含关系或 2-gram Dice ≥ 阈值），命中则复用它。
     */
    private Folder findSimilarFolder(String name, List<Folder> folders) {
        String normalized = normalizeFolderName(name);
        if (normalized.length() < 2) {
            return null;
        }
        Folder best = null;
        double bestScore = 0;
        for (Folder folder : folders) {
            String other = normalizeFolderName(folder.getName());
            if (other.length() < 2) {
                continue;
            }
            if (normalized.contains(other) || other.contains(normalized)) {
                return folder;
            }
            double score = bigramDice(normalized, other);
            if (score > bestScore) {
                bestScore = score;
                best = folder;
            }
        }
        return bestScore >= SIMILAR_FOLDER_NAME_THRESHOLD ? best : null;
    }

    private String normalizeFolderName(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？、：；（）【】“”‘’]+", "");
    }

    private double bigramDice(String left, String right) {
        Set<String> leftGrams = bigrams(left);
        Set<String> rightGrams = bigrams(right);
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) {
            return 0;
        }
        int intersection = 0;
        for (String gram : leftGrams) {
            if (rightGrams.contains(gram)) {
                intersection++;
            }
        }
        return 2.0 * intersection / (leftGrams.size() + rightGrams.size());
    }

    private Set<String> bigrams(String value) {
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            grams.add(value.substring(i, i + 2));
        }
        return grams;
    }

    private Long createAutoFolder(String name, String description) {
        Folder folder = new Folder();
        folder.setName(name);
        folder.setDescription(description == null ? null
                : description.length() <= 256 ? description : description.substring(0, 256));
        folder.setAutoCreated(true);
        try {
            folderMapper.insert(folder);
            return folder.getId();
        } catch (DuplicateKeyException e) {
            Folder existing = folderMapper.selectOne(Wrappers.<Folder>lambdaQuery().eq(Folder::getName, name).last("limit 1"));
            return existing == null ? null : existing.getId();
        }
    }

    private boolean insertIfAbsent(long assetId, long folderId) {
        Long existing = assetFolderMapper.selectCount(Wrappers.<AssetFolder>lambdaQuery()
                .eq(AssetFolder::getAssetId, assetId)
                .eq(AssetFolder::getFolderId, folderId));
        if (existing != null && existing > 0) {
            return false;
        }
        AssetFolder row = new AssetFolder();
        row.setAssetId(assetId);
        row.setFolderId(folderId);
        try {
            assetFolderMapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private void replaceMembership(long assetId, Long folderId) {
        assetFolderMapper.delete(Wrappers.<AssetFolder>lambdaQuery().eq(AssetFolder::getAssetId, assetId));
        if (folderId != null) {
            insertIfAbsent(assetId, folderId);
        }
    }

    private List<Long> activeAssetIds(List<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        if (assetIds.size() > 200) {
            throw ApiException.badRequest("单次最多批量操作 200 张");
        }
        List<Long> distinct = assetIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        return assetMapper.selectList(Wrappers.<Asset>lambdaQuery()
                        .in(Asset::getId, distinct)
                        .eq(Asset::getDeleted, false))
                .stream()
                .map(Asset::getId)
                .toList();
    }

    private long activeCount(long folderId) {
        return assetFolderMapper.counts().stream()
                .filter(count -> Objects.equals(count.folderId(), folderId))
                .mapToLong(AssetFolderMapper.FolderCount::total)
                .findFirst()
                .orElse(0L);
    }

    private Asset requireActiveAsset(Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
            throw ApiException.notFound("资产不存在");
        }
        return asset;
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
}
