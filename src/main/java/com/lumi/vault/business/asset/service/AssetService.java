package com.lumi.vault.business.asset.service;

import lombok.extern.slf4j.Slf4j;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumi.vault.business.asset.dto.AssetContentResponse;
import com.lumi.vault.business.asset.dto.AssetDetailResponse;
import com.lumi.vault.business.asset.dto.AssetResponse;
import com.lumi.vault.business.asset.dto.AssetUrlResponse;
import com.lumi.vault.business.asset.dto.SimilarAssetResponse;
import com.lumi.vault.business.asset.dto.TrashAssetResponse;
import com.lumi.vault.business.asset.dto.UpdateAssetRequest;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.event.AssetPurgingEvent;
import com.lumi.vault.business.asset.event.AssetUploadedEvent;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.asset.storage.AssetStorage;
import com.lumi.vault.common.NaturalSortKey;
import com.lumi.vault.common.PageResult;
import com.lumi.vault.config.StorageProperties;
import com.lumi.vault.business.folder.service.FolderService;
import com.lumi.vault.business.graph.service.AssetSimilarityGraphService;
import com.lumi.vault.exception.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AssetService {
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int PURGE_BATCH_SIZE = 200;

    private final AssetMapper assetMapper;
    private final AssetStorage assetStorage;
    private final ImageProcessor imageProcessor;
    private final AssetSettingsService settingsService;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final FolderService folderService;
    private final AssetSimilarityGraphService similarityGraphService;

    public AssetService(AssetMapper assetMapper,
                        AssetStorage assetStorage,
                        ImageProcessor imageProcessor,
                        AssetSettingsService settingsService,
                        StorageProperties storageProperties,
                        ApplicationEventPublisher eventPublisher,
                        FolderService folderService,
                        AssetSimilarityGraphService similarityGraphService) {
        this.assetMapper = assetMapper;
        this.assetStorage = assetStorage;
        this.imageProcessor = imageProcessor;
        this.settingsService = settingsService;
        this.storageProperties = storageProperties;
        this.eventPublisher = eventPublisher;
        this.folderService = folderService;
        this.similarityGraphService = similarityGraphService;
    }

    /**
     * 「相关图片」：相似度图的直接邻居（无向、稳定），已过滤软删除资产。
     */
    public List<SimilarAssetResponse> related(Long id, int limit) {
        requireAsset(id, false);
        int clamped = Math.min(Math.max(limit, 1), 50);
        List<AssetSimilarityGraphService.Neighbor> neighbors = similarityGraphService.neighborsOf(id, clamped);
        if (neighbors.isEmpty()) {
            return List.of();
        }
        Map<Long, Asset> assets = assetMapper.selectList(Wrappers.<Asset>lambdaQuery()
                        .in(Asset::getId, neighbors.stream().map(AssetSimilarityGraphService.Neighbor::assetId).toList())
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

    @Transactional
    public AssetResponse upload(MultipartFile file, String description, Long folderId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("文件不能为空");
        }
        if (folderId != null) {
            folderService.requireFolder(folderId);
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw ApiException.badRequest("仅支持图片文件");
        }
        Path tempFile = Files.createTempFile("asset-upload-", ".tmp");
        Path thumbnailFile = null;
        try {
            file.transferTo(tempFile);
            String sha256 = sha256Hex(tempFile);
            Asset existing = findBySha256(sha256);
            if (existing != null) {
                if (!Boolean.TRUE.equals(existing.getDeleted())) {
                    throw ApiException.conflict("图片已存在");
                }
                restore(existing);
                return AssetResponse.from(existing, folderService.folderIdsOf(existing.getId()));
            }

            String originalFilename = sanitizeFilename(file.getOriginalFilename());
            ImageProcessor.ProcessedImage processed = imageProcessor.process(tempFile, contentType);
            thumbnailFile = processed.thumbnail();

            String baseKey = "assets/" + LocalDate.now().format(MONTH_FORMATTER) + "/" + sha256;
            String storageKey = baseKey + extension(originalFilename, contentType);
            assetStorage.put(storageKey, tempFile, contentType);

            String thumbnailKey = null;
            if (thumbnailFile != null) {
                thumbnailKey = "thumbs/" + LocalDate.now().format(MONTH_FORMATTER) + "/" + sha256
                        + extensionForContentType(processed.thumbnailContentType());
                assetStorage.put(thumbnailKey, thumbnailFile, processed.thumbnailContentType());
            }

            Asset asset = new Asset();
            asset.setStorageKey(storageKey);
            asset.setThumbnailKey(thumbnailKey);
            asset.setOriginalFilename(originalFilename);
            asset.setSortKey(NaturalSortKey.of(originalFilename));
            asset.setAuthor(processed.metadata() == null ? null : processed.metadata().author());
            asset.setContentType(contentType);
            asset.setSizeBytes(Files.size(tempFile));
            asset.setWidth(processed.width());
            asset.setHeight(processed.height());
            asset.setSha256(sha256);
            asset.setDescription(description);
            asset.setColors(processed.colors());
            asset.setMetadata(processed.metadata());
            asset.setDeleted(false);
            try {
                assetMapper.insert(asset);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("图片已存在");
            }
            folderService.attach(asset.getId(), folderId);
            eventPublisher.publishEvent(new AssetUploadedEvent(asset.getId()));
            return AssetResponse.from(asset, folderId == null ? List.of() : List.of(folderId));
        } finally {
            Files.deleteIfExists(tempFile);
            if (thumbnailFile != null) {
                Files.deleteIfExists(thumbnailFile);
            }
        }
    }

    public PageResult<AssetResponse> list(long page, long size, String keyword, Long folderId, boolean uncategorized,
                                          String sort) {
        LambdaQueryWrapper<Asset> query = Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getDeleted, false);
        if (keyword != null && !keyword.isBlank()) {
            query.like(Asset::getOriginalFilename, keyword.trim());
        }
        applyFolderFilter(query, folderId, uncategorized);
        applySort(query, sort);
        Page<Asset> result = page(page, size, query);
        Map<Long, List<Long>> folderIds = folderService.folderIdsByAssets(
                result.getRecords().stream().map(Asset::getId).toList());
        return PageResult.of(result, asset -> AssetResponse.from(asset, folderIds.getOrDefault(asset.getId(), List.of())));
    }

    public PageResult<AssetResponse> search(long page, long size, String filename, String author, boolean exact,
                                            Long folderId, boolean uncategorized, String sort) {
        LambdaQueryWrapper<Asset> query = Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getDeleted, false);
        if (filename != null && !filename.isBlank()) {
            String value = filename.trim();
            if (exact) {
                query.apply("lower(original_filename) = lower({0})", value);
            } else {
                query.apply("lower(original_filename) like lower({0})", "%" + value + "%");
            }
        }
        if (author != null && !author.isBlank()) {
            String value = author.trim();
            if (exact) {
                query.apply("lower(author) = lower({0})", value);
            } else {
                query.apply("lower(author) like lower({0})", "%" + value + "%");
            }
        }
        applyFolderFilter(query, folderId, uncategorized);
        applySort(query, sort);
        Page<Asset> result = page(page, size, query);
        Map<Long, List<Long>> folderIds = folderService.folderIdsByAssets(
                result.getRecords().stream().map(Asset::getId).toList());
        return PageResult.of(result, asset -> AssetResponse.from(asset, folderIds.getOrDefault(asset.getId(), List.of())));
    }

    private void applySort(LambdaQueryWrapper<Asset> query, String sort) {
        String value = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "name_desc" -> query.orderByDesc(Asset::getSortKey).orderByDesc(Asset::getId);
            case "created_desc" -> query.orderByDesc(Asset::getCreatedAt).orderByDesc(Asset::getId);
            case "created_asc" -> query.orderByAsc(Asset::getCreatedAt).orderByAsc(Asset::getId);
            case "size_desc" -> query.orderByDesc(Asset::getSizeBytes).orderByDesc(Asset::getId);
            case "size_asc" -> query.orderByAsc(Asset::getSizeBytes).orderByAsc(Asset::getId);
            default -> query.orderByAsc(Asset::getSortKey).orderByAsc(Asset::getId);
        }
    }

    private void applyFolderFilter(LambdaQueryWrapper<Asset> query, Long folderId, boolean uncategorized) {
        if (folderId != null) {
            query.exists("select 1 from asset_folder af where af.asset_id = asset.id and af.folder_id = {0}", folderId);
        } else if (uncategorized) {
            query.notExists("select 1 from asset_folder af where af.asset_id = asset.id");
        }
    }

    public AssetDetailResponse detail(Long id) {
        Asset asset = requireAsset(id, false);
        String url = presignUrl(asset.getStorageKey(), asset.getOriginalFilename(), false);
        String thumbnailUrl = asset.getThumbnailKey() == null ? null
                : presignUrl(asset.getThumbnailKey(), asset.getOriginalFilename(), false);
        return AssetDetailResponse.from(asset, url, thumbnailUrl, folderService.folderIdsOf(id));
    }

    @Transactional
    public AssetResponse update(Long id, UpdateAssetRequest request) {
        Asset asset = requireAsset(id, false);
        boolean changed = false;
        if (request.originalFilename() != null && !request.originalFilename().isBlank()) {
            String filename = sanitizeFilename(request.originalFilename());
            asset.setOriginalFilename(filename);
            asset.setSortKey(NaturalSortKey.of(filename));
            changed = true;
        }
        if (request.author() != null) {
            asset.setAuthor(request.author().isBlank() ? null : request.author().trim());
            changed = true;
        }
        if (request.description() != null) {
            asset.setDescription(request.description());
            changed = true;
        }
        if (changed) {
            assetMapper.updateById(asset);
        }
        return AssetResponse.from(asset, folderService.folderIdsOf(asset.getId()));
    }

    @Transactional
    public void delete(Long id) {
        requireAsset(id, false);
        Instant now = Instant.now();
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, id)
                .set(Asset::getDeleted, true)
                .set(Asset::getDeletedAt, now)
                .set(Asset::getUpdatedAt, now));
    }

    @Transactional
    public int deleteBatch(List<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return 0;
        }
        if (assetIds.size() > 200) {
            throw ApiException.badRequest("单次最多批量操作 200 张");
        }
        Instant now = Instant.now();
        int deleted = 0;
        for (Long id : assetIds) {
            Asset asset = assetMapper.selectById(id);
            if (asset == null || Boolean.TRUE.equals(asset.getDeleted())) {
                continue;
            }
            assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                    .eq(Asset::getId, id)
                    .set(Asset::getDeleted, true)
                    .set(Asset::getDeletedAt, now)
                    .set(Asset::getUpdatedAt, now));
            deleted++;
        }
        return deleted;
    }

    @Transactional
    public int restoreBatch(List<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return 0;
        }
        if (assetIds.size() > 200) {
            throw ApiException.badRequest("单次最多批量操作 200 张");
        }
        Instant now = Instant.now();
        int restored = 0;
        for (Long id : assetIds) {
            Asset asset = assetMapper.selectById(id);
            if (asset == null || !Boolean.TRUE.equals(asset.getDeleted())) {
                continue;
            }
            assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                    .eq(Asset::getId, id)
                    .set(Asset::getDeleted, false)
                    .set(Asset::getDeletedAt, null)
                    .set(Asset::getUpdatedAt, now));
            restored++;
        }
        return restored;
    }

    @Transactional
    public int purgeBatch(List<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return 0;
        }
        if (assetIds.size() > 200) {
            throw ApiException.badRequest("单次最多批量操作 200 张");
        }
        int purged = 0;
        for (Long id : assetIds) {
            Asset asset = assetMapper.selectById(id);
            if (asset == null || !Boolean.TRUE.equals(asset.getDeleted())) {
                continue;
            }
            deleteObjects(asset);
            eventPublisher.publishEvent(new AssetPurgingEvent(id));
            assetMapper.deleteById(id);
            purged++;
        }
        return purged;
    }

    public PageResult<TrashAssetResponse> trash(long page, long size) {
        int retentionDays = settingsService.retentionDays();
        LambdaQueryWrapper<Asset> query = Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getDeleted, true)
                .orderByDesc(Asset::getDeletedAt)
                .orderByDesc(Asset::getId);
        return PageResult.of(page(page, size, query), asset -> TrashAssetResponse.from(asset,
                asset.getDeletedAt() == null ? null : asset.getDeletedAt().plus(Duration.ofDays(retentionDays))));
    }

    @Transactional
    public void restore(Long id) {
        restore(requireAsset(id, true));
    }

    @Transactional
    public void purge(Long id) {
        Asset asset = requireAsset(id, true);
        deleteObjects(asset);
        eventPublisher.publishEvent(new AssetPurgingEvent(id));
        assetMapper.deleteById(id);
    }

    @Transactional
    public int purgeExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(settingsService.retentionDays()));
        List<Asset> expired = assetMapper.selectList(Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getDeleted, true)
                .isNotNull(Asset::getDeletedAt)
                .le(Asset::getDeletedAt, cutoff)
                .orderByAsc(Asset::getDeletedAt)
                .last("limit " + PURGE_BATCH_SIZE));
        for (Asset asset : expired) {
            deleteObjects(asset);
            eventPublisher.publishEvent(new AssetPurgingEvent(asset.getId()));
            assetMapper.deleteById(asset.getId());
        }
        return expired.size();
    }

    public AssetUrlResponse presign(Long id, boolean download) {
        Asset asset = requireAsset(id, false);
        return new AssetUrlResponse(
                presignUrl(asset.getStorageKey(), asset.getOriginalFilename(), download),
                Instant.now().plus(storageProperties.presignExpiry()));
    }

    /**
     * 直接读取资产字节。{@code thumbnail=true} 时优先返回缩略图，缩略图缺失则回退原图。
     */
    public AssetContentResponse content(Long id, boolean thumbnail) {
        Asset asset = requireAsset(id, false);
        boolean useThumbnail = thumbnail && asset.getThumbnailKey() != null;
        String key = useThumbnail ? asset.getThumbnailKey() : asset.getStorageKey();
        String contentType = useThumbnail
                ? ("image/png".equalsIgnoreCase(asset.getContentType()) ? "image/png" : "image/jpeg")
                : asset.getContentType();
        return new AssetContentResponse(assetStorage.get(key), contentType, asset.getOriginalFilename());
    }

    private void restore(Asset asset) {
        Instant now = Instant.now();
        assetMapper.update(null, Wrappers.<Asset>lambdaUpdate()
                .eq(Asset::getId, asset.getId())
                .set(Asset::getDeleted, false)
                .set(Asset::getDeletedAt, null)
                .set(Asset::getUpdatedAt, now));
        asset.setDeleted(false);
        asset.setDeletedAt(null);
        asset.setUpdatedAt(now);
    }

    private void deleteObjects(Asset asset) {
        try {
            assetStorage.delete(asset.getStorageKey());
        } catch (Exception e) {
            log.warn("删除存储对象失败: {} ({})", asset.getStorageKey(), e.getMessage());
        }
        if (asset.getThumbnailKey() != null) {
            try {
                assetStorage.delete(asset.getThumbnailKey());
            } catch (Exception e) {
                log.warn("删除缩略图对象失败: {} ({})", asset.getThumbnailKey(), e.getMessage());
            }
        }
    }

    private Asset requireAsset(Long id, boolean deleted) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null || !Objects.equals(asset.getDeleted(), deleted)) {
            throw ApiException.notFound(deleted ? "回收站中不存在该资产" : "资产不存在");
        }
        return asset;
    }

    private Asset findBySha256(String sha256) {
        return assetMapper.selectOne(Wrappers.<Asset>lambdaQuery()
                .eq(Asset::getSha256, sha256)
                .last("limit 1"));
    }

    private Page<Asset> page(long page, long size, Wrapper<Asset> wrapper) {
        long current = Math.max(page, 1);
        long pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return assetMapper.selectPage(new Page<>(current, pageSize), wrapper);
    }

    private String presignUrl(String key, String filename, boolean download) {
        URL url = assetStorage.presignGet(key, filename, download, storageProperties.presignExpiry());
        return url.toString();
    }

    private String sha256Hex(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "image";
        }
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.length() > 512 ? name.substring(0, 512) : name;
    }

    private String extension(String filename, String contentType) {
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && dot < filename.length() - 1) {
            String extension = filename.substring(dot).toLowerCase();
            if (extension.matches("\\.[a-z0-9]{1,8}")) {
                return extension;
            }
        }
        return extensionForContentType(contentType);
    }

    private String extensionForContentType(String contentType) {
        if (contentType == null) {
            return ".jpg";
        }
        return switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/bmp" -> ".bmp";
            default -> ".jpg";
        };
    }
}
