package com.lumi.vault.business.folder.controller;

import com.lumi.vault.business.folder.dto.FolderAssignRequest;
import com.lumi.vault.business.folder.dto.FolderBatchAssignRequest;
import com.lumi.vault.business.folder.dto.FolderBatchClassifyRequest;
import com.lumi.vault.business.folder.dto.FolderMergeRequest;
import com.lumi.vault.business.folder.dto.FolderRequest;
import com.lumi.vault.business.folder.dto.FolderResponse;
import com.lumi.vault.business.folder.service.FolderService;
import com.lumi.vault.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @GetMapping
    public Result<List<FolderResponse>> list() {
        return Result.ok(folderService.list());
    }

    @PostMapping
    public Result<FolderResponse> create(@Valid @RequestBody FolderRequest request) {
        return Result.ok(folderService.create(request));
    }

    @PutMapping("/{id}")
    public Result<FolderResponse> update(@PathVariable Long id, @Valid @RequestBody FolderRequest request) {
        return Result.ok(folderService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        folderService.delete(id);
        return Result.ok();
    }

    @PutMapping("/assets")
    public Result<Integer> assignBatch(@Valid @RequestBody FolderBatchAssignRequest request) {
        return Result.ok(folderService.assignBatch(request.assetIds(), request.folderId()));
    }

    @PostMapping("/assets/add")
    public Result<Integer> addBatch(@Valid @RequestBody FolderBatchAssignRequest request) {
        return Result.ok(folderService.addBatch(request.assetIds(), request.folderId()));
    }

    @PostMapping("/assets/remove")
    public Result<Integer> removeBatch(@Valid @RequestBody FolderBatchAssignRequest request) {
        return Result.ok(folderService.removeBatch(request.assetIds(), request.folderId()));
    }

    @PostMapping("/classify")
    public Result<Integer> classifyBatch(@Valid @RequestBody FolderBatchClassifyRequest request) {
        return Result.ok(folderService.classifyBatch(request.assetIds()));
    }

    @PutMapping("/assets/{assetId}")
    public Result<Void> assign(@PathVariable Long assetId, @RequestBody FolderAssignRequest request) {
        folderService.assign(assetId, request.folderId());
        return Result.ok();
    }

    @PostMapping("/classify/{assetId}")
    public Result<Void> classify(@PathVariable Long assetId) {
        folderService.classify(assetId);
        return Result.ok();
    }

    @PostMapping("/{id}/merge")
    public Result<Integer> merge(@PathVariable Long id, @Valid @RequestBody FolderMergeRequest request) {
        return Result.ok(folderService.merge(id, request.targetFolderId()));
    }
}
