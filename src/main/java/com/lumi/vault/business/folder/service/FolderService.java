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
import com.lumi.vault.business.folder.entity.Folder;
import com.lumi.vault.business.folder.mapper.FolderMapper;
import com.lumi.vault.exception.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class FolderService {

    private static final String CLASSIFY_SYSTEM_PROMPT = """
            你是图片管理助手。请把图片归类到最合适的已有文件夹；若都不合适，可以建议一个新文件夹。
            只输出 JSON，不要输出其他内容：
            {"folderId": 已有文件夹id 或 null, "newFolderName": "新文件夹名或 null"}
            规则：
            1. 优先选择语义匹配的已有文件夹，把它的 id 填入 folderId；
            2. 仅当已有文件夹都不匹配时，给出 2-8 个字的通用分类名（如 风景、人物、动物、UI素材、动漫角色），newFolderName 填名字，folderId 填 null；
            3. 无法判断分类时两者都填 null。""";

    private final FolderMapper folderMapper;
    private final AssetMapper assetMapper;
    private final AssetEmbeddingMapper embeddingMapper;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public FolderService(FolderMapper folderMapper,
                         AssetMapper assetMapper,
                         AssetEmbeddingMapper embeddingMapper,
                         AiClient aiClient,
                         ObjectMapper objectMapper) {
        this.folderMapper = folderMapper;
        this.assetMapper = assetMapper;
        this.embeddingMapper = embeddingMapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    public List<FolderResponse> list() {
        return folderMapper.selectList(Wrappers.<Folder>lambdaQuery().orderByAsc(Folder::getName))
                .stream()
                .map(folder -> FolderResponse.of(folder, assetMapper.selectCount(Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getDeleted, false)
                        .eq(Asset::getFolderId, folder.getId()))))
                .toList();
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
        return FolderResponse.of(folderMapper.selectById(id), assetMapper.selectCount(Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getDeleted, false)
                .eq(Asset::getFolderId, id)));
    }

    @Transactional
    public void delete(Long id) {
        requireFolder(id);
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getFolderId, id)
                .set(Asset::getFolderId, null));
        folderMapper.deleteById(id);
    }

    @Transactional
    public void assign(Long assetId, Long folderId) {
        Asset asset = requireActiveAsset(assetId);
        if (folderId != null) {
            requireFolder(folderId);
        }
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, assetId)
                .set(Asset::getFolderId, folderId));
        asset.setFolderId(folderId);
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
        if (asset.getFolderId() != null) {
            return;
        }
        doClassify(asset, description, tags);
    }

    @Transactional
    public int assignBatch(List<Long> assetIds, Long folderId) {
        if (assetIds == null || assetIds.isEmpty()) {
            return 0;
        }
        if (assetIds.size() > 200) {
            throw ApiException.badRequest("单次最多批量操作 200 张");
        }
        if (folderId != null) {
            requireFolder(folderId);
        }
        return assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .in(Asset::getId, assetIds)
                .eq(Asset::getDeleted, false)
                .set(Asset::getFolderId, folderId));
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
                    assignInternal(asset, folderId);
                    return true;
                }
            }
            String newName = root.path("newFolderName").asString("").trim();
            if (!newName.isBlank()) {
                Folder existing = folderMapper.selectOne(Wrappers.<Folder>lambdaQuery()
                        .eq(Folder::getName, newName)
                        .last("limit 1"));
                Long targetId = existing != null ? existing.getId()
                        : createAutoFolder(newName, hasDescription ? description : null);
                assignInternal(asset, targetId);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
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

    private void assignInternal(Asset asset, Long folderId) {
        if (folderId == null) {
            return;
        }
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, asset.getId())
                .set(Asset::getFolderId, folderId));
        asset.setFolderId(folderId);
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
