package com.lumi.vault.business.asset.controller;

import com.lumi.vault.business.asset.dto.AssetDetailResponse;
import com.lumi.vault.business.asset.dto.AssetBatchRequest;
import com.lumi.vault.business.asset.dto.AssetResponse;
import com.lumi.vault.business.asset.dto.AssetSettingsRequest;
import com.lumi.vault.business.asset.dto.AssetSettingsResponse;
import com.lumi.vault.business.asset.dto.AssetUrlResponse;
import com.lumi.vault.business.asset.dto.TrashAssetResponse;
import com.lumi.vault.business.asset.dto.UpdateAssetRequest;
import com.lumi.vault.business.asset.service.AssetService;
import com.lumi.vault.business.asset.service.AssetSettingsService;
import com.lumi.vault.common.PageResult;
import com.lumi.vault.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final AssetService assetService;
    private final AssetSettingsService settingsService;

    public AssetController(AssetService assetService, AssetSettingsService settingsService) {
        this.assetService = assetService;
        this.settingsService = settingsService;
    }

    @GetMapping
    public Result<PageResult<AssetResponse>> list(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Long folderId,
                                                  @RequestParam(defaultValue = "false") boolean uncategorized) {
        return Result.ok(assetService.list(page, size, keyword, folderId, uncategorized));
    }

    @GetMapping("/search")
    public Result<PageResult<AssetResponse>> search(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    @RequestParam(required = false) String filename,
                                                    @RequestParam(required = false) String author,
                                                    @RequestParam(defaultValue = "false") boolean exact,
                                                    @RequestParam(required = false) Long folderId,
                                                    @RequestParam(defaultValue = "false") boolean uncategorized) {
        return Result.ok(assetService.search(page, size, filename, author, exact, folderId, uncategorized));
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

    @GetMapping("/{id}/url")
    public Result<AssetUrlResponse> url(@PathVariable Long id,
                                        @RequestParam(defaultValue = "false") boolean download) {
        return Result.ok(assetService.presign(id, download));
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
}
