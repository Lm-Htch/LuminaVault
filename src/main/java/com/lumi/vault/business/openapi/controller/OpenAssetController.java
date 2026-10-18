package com.lumi.vault.business.openapi.controller;

import com.lumi.vault.business.asset.dto.AssetBatchRequest;
import com.lumi.vault.business.asset.dto.AssetContentResponse;
import com.lumi.vault.business.asset.dto.AssetDetailResponse;
import com.lumi.vault.business.asset.dto.AssetResponse;
import com.lumi.vault.business.asset.dto.AssetSettingsRequest;
import com.lumi.vault.business.asset.dto.AssetSettingsResponse;
import com.lumi.vault.business.asset.dto.AssetUrlResponse;
import com.lumi.vault.business.asset.dto.SimilarAssetResponse;
import com.lumi.vault.business.asset.dto.TrashAssetResponse;
import com.lumi.vault.business.asset.dto.UpdateAssetRequest;
import com.lumi.vault.business.asset.service.AssetService;
import com.lumi.vault.business.asset.service.AssetSettingsService;
import com.lumi.vault.business.asset.service.AssetSimilarityService;
import com.lumi.vault.common.PageResult;
import com.lumi.vault.common.Result;
import com.lumi.vault.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 开放 API：资产管理（供 MCP / 外部脚本调用）。
 *
 * <p>与站内 {@code /api/assets} 同源同语义，区别只有两点：鉴权用 API 密钥，
 * 且额外提供 {@code /{id}/content} 直接回图片字节 —— 远端调用方未必能访问 SeaweedFS 预签名地址。</p>
 */
@RestController
@RequestMapping("/api/open/v1/assets")
public class OpenAssetController {

    private final AssetService assetService;
    private final AssetSettingsService settingsService;
    private final AssetSimilarityService similarityService;

    public OpenAssetController(AssetService assetService, AssetSettingsService settingsService,
                               AssetSimilarityService similarityService) {
        this.assetService = assetService;
        this.settingsService = settingsService;
        this.similarityService = similarityService;
    }

    @GetMapping
    public Result<PageResult<AssetResponse>> list(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Long folderId,
                                                  @RequestParam(defaultValue = "false") boolean uncategorized,
                                                  @RequestParam(required = false) String sort) {
        return Result.ok(assetService.list(page, size, keyword, folderId, uncategorized, sort));
    }

    @GetMapping("/search")
    public Result<PageResult<AssetResponse>> search(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    @RequestParam(required = false) String filename,
                                                    @RequestParam(required = false) String author,
                                                    @RequestParam(defaultValue = "false") boolean exact,
                                                    @RequestParam(required = false) Long folderId,
                                                    @RequestParam(defaultValue = "false") boolean uncategorized,
                                                    @RequestParam(required = false) String sort) {
        return Result.ok(assetService.search(page, size, filename, author, exact, folderId, uncategorized, sort));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<AssetResponse> upload(@RequestPart("file") MultipartFile file,
                                        @RequestParam(required = false) String description,
                                        @RequestParam(required = false) Long folderId) throws IOException {
        return Result.ok(assetService.upload(file, description, folderId));
    }

    @GetMapping("/trash")
    public Result<PageResult<TrashAssetResponse>> trash(@RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "20") long size) {
        return Result.ok(assetService.trash(page, size));
    }

    @GetMapping("/settings")
    public Result<AssetSettingsResponse> settings() {
        return Result.ok(new AssetSettingsResponse(settingsService.retentionDays()));
    }

    @PutMapping("/settings")
    public Result<AssetSettingsResponse> updateSettings(@Valid @RequestBody AssetSettingsRequest request) {
        return Result.ok(new AssetSettingsResponse(settingsService.updateRetentionDays(request.retentionDays())));
    }

    @GetMapping("/{id}")
    public Result<AssetDetailResponse> detail(@PathVariable Long id) {
        return Result.ok(assetService.detail(id));
    }

    @PutMapping("/{id}")
    public Result<AssetResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateAssetRequest request) {
        return Result.ok(assetService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        assetService.delete(id);
        return Result.ok();
    }

    @PostMapping("/batch/delete")
    public Result<Integer> deleteBatch(@Valid @RequestBody AssetBatchRequest request) {
        return Result.ok(assetService.deleteBatch(request.assetIds()));
    }

    @PostMapping("/{id}/restore")
    public Result<Void> restore(@PathVariable Long id) {
        assetService.restore(id);
        return Result.ok();
    }

    @PostMapping("/batch/restore")
    public Result<Integer> restoreBatch(@Valid @RequestBody AssetBatchRequest request) {
        return Result.ok(assetService.restoreBatch(request.assetIds()));
    }

    @PostMapping("/batch/purge")
    public Result<Integer> purgeBatch(@Valid @RequestBody AssetBatchRequest request) {
        return Result.ok(assetService.purgeBatch(request.assetIds()));
    }

    @DeleteMapping("/{id}/purge")
    public Result<Void> purge(@PathVariable Long id) {
        assetService.purge(id);
        return Result.ok();
    }

    @GetMapping("/{id}/url")
    public Result<AssetUrlResponse> url(@PathVariable Long id,
                                        @RequestParam(defaultValue = "false") boolean download) {
        return Result.ok(assetService.presign(id, download));
    }

    /** 直接下载图片字节，{@code thumbnail=true} 取缩略图。 */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id,
                                          @RequestParam(defaultValue = "false") boolean thumbnail) {
        AssetContentResponse content = assetService.content(id, thumbnail);
        String filename = content.filename() == null ? ("asset-" + id) : content.filename();
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(
                    content.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : content.contentType());
        } catch (RuntimeException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(content.bytes());
    }

    @GetMapping("/{id}/similar")
    public Result<List<SimilarAssetResponse>> similar(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "12") int limit) {
        return Result.ok(similarityService.similarByAsset(id, limit));
    }

    @GetMapping("/{id}/related")
    public Result<List<SimilarAssetResponse>> related(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "12") int limit) {
        return Result.ok(assetService.related(id, limit));
    }

    @PostMapping("/similar-search")
    public Result<List<SimilarAssetResponse>> similarSearch(@RequestPart("file") MultipartFile file,
                                                            @RequestParam(defaultValue = "12") int limit)
            throws IOException {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw ApiException.badRequest("仅支持图片文件");
        }
        return Result.ok(similarityService.similarByImage(file.getBytes(), contentType, limit));
    }
}
