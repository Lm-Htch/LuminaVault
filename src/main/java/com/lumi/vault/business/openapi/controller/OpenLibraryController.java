package com.lumi.vault.business.openapi.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.service.AssetEnrichmentService;
import com.lumi.vault.business.apikey.entity.ApiKey;
import com.lumi.vault.business.apikey.service.ApiKeyService;
import com.lumi.vault.business.asset.dto.AssetBatchRequest;
import com.lumi.vault.business.asset.entity.Asset;
import com.lumi.vault.business.asset.mapper.AssetMapper;
import com.lumi.vault.business.auth.security.AuthenticatedUser;
import com.lumi.vault.business.character.dto.CharacterResponse;
import com.lumi.vault.business.character.entity.Character;
import com.lumi.vault.business.character.mapper.CharacterMapper;
import com.lumi.vault.business.character.service.CharacterService;
import com.lumi.vault.business.folder.dto.FolderRequest;
import com.lumi.vault.business.folder.dto.FolderResponse;
import com.lumi.vault.business.folder.entity.Folder;
import com.lumi.vault.business.folder.mapper.FolderMapper;
import com.lumi.vault.business.folder.service.FolderService;
import com.lumi.vault.business.graph.service.AssetSimilarityGraphService;
import com.lumi.vault.business.openapi.dto.OpenAccountResponse;
import com.lumi.vault.business.openapi.dto.OpenStatsResponse;
import com.lumi.vault.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 开放 API：身份、概览、文件夹与角色等图库级管理。
 */
@RestController
@RequestMapping("/api/open/v1")
public class OpenLibraryController {

    private static final String API_KEY_TOKEN_PREFIX = "apikey:";
    private static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

    private final AssetMapper assetMapper;
    private final FolderMapper folderMapper;
    private final CharacterMapper characterMapper;
    private final FolderService folderService;
    private final CharacterService characterService;
    private final AssetSimilarityGraphService graphService;
    private final AssetEnrichmentService enrichmentService;
    private final ApiKeyService apiKeyService;

    public OpenLibraryController(AssetMapper assetMapper,
                                 FolderMapper folderMapper,
                                 CharacterMapper characterMapper,
                                 FolderService folderService,
                                 CharacterService characterService,
                                 AssetSimilarityGraphService graphService,
                                 AssetEnrichmentService enrichmentService,
                                 ApiKeyService apiKeyService) {
        this.assetMapper = assetMapper;
        this.folderMapper = folderMapper;
        this.characterMapper = characterMapper;
        this.folderService = folderService;
        this.characterService = characterService;
        this.graphService = graphService;
        this.enrichmentService = enrichmentService;
        this.apiKeyService = apiKeyService;
    }

    @GetMapping("/account")
    public Result<OpenAccountResponse> account(@AuthenticationPrincipal AuthenticatedUser principal,
                                               Authentication authentication) {
        List<String> scopes = authentication == null ? List.of()
                : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(SCOPE_AUTHORITY_PREFIX))
                .map(authority -> authority.substring(SCOPE_AUTHORITY_PREFIX.length()))
                .toList();
        Long keyId = parseKeyId(principal.tokenId());
        ApiKey key = keyId == null ? null : apiKeyService.find(keyId).orElse(null);
        return Result.ok(new OpenAccountResponse(
                principal.id(),
                principal.username(),
                keyId,
                key == null ? null : key.getName(),
                scopes,
                key == null ? null : key.getExpiresAt(),
                key == null ? null : key.getLastUsedAt()));
    }

    @GetMapping("/stats")
    public Result<OpenStatsResponse> stats() {
        long assets = assetMapper.selectCount(Wrappers.<Asset>lambdaQuery().eq(Asset::getDeleted, false));
        long trash = assetMapper.selectCount(Wrappers.<Asset>lambdaQuery().eq(Asset::getDeleted, true));
        long folders = folderMapper.selectCount(Wrappers.<Folder>lambdaQuery());
        long characters = characterMapper.selectCount(Wrappers.<Character>lambdaQuery());
        return Result.ok(new OpenStatsResponse(assets, trash, folders, characters, graphService.edgeCount()));
    }

    // ---------------------------------------------------------------- 文件夹

    @GetMapping("/folders")
    public Result<List<FolderResponse>> folders() {
        return Result.ok(folderService.list());
    }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<FolderResponse> createFolder(@Valid @RequestBody FolderRequest request) {
        return Result.ok(folderService.create(request));
    }

    @PutMapping("/folders/{id}")
    public Result<FolderResponse> updateFolder(@PathVariable Long id, @Valid @RequestBody FolderRequest request) {
        return Result.ok(folderService.update(id, request));
    }

    @DeleteMapping("/folders/{id}")
    public Result<Void> deleteFolder(@PathVariable Long id) {
        folderService.delete(id);
        return Result.ok();
    }

    /** 把若干资产移入该文件夹（覆盖式：先清空这些资产的文件夹再挂上）。 */
    @PostMapping("/folders/{id}/assets")
    public Result<Integer> assignFolder(@PathVariable Long id, @Valid @RequestBody AssetBatchRequest request) {
        return Result.ok(folderService.assignBatch(request.assetIds(), id));
    }

    @PostMapping("/folders/{id}/assets/add")
    public Result<Integer> addToFolder(@PathVariable Long id, @Valid @RequestBody AssetBatchRequest request) {
        return Result.ok(folderService.addBatch(request.assetIds(), id));
    }

    @PostMapping("/folders/{id}/assets/remove")
    public Result<Integer> removeFromFolder(@PathVariable Long id, @Valid @RequestBody AssetBatchRequest request) {
        return Result.ok(folderService.removeBatch(request.assetIds(), id));
    }

    // ---------------------------------------------------------------- 角色

    @GetMapping("/characters")
    public Result<List<CharacterResponse>> characters() {
        return Result.ok(characterService.list());
    }

    // ---------------------------------------------------------------- 分析

    /**
     * 触发 AI 分析（默认异步，避免 MCP 调用被长耗时阻塞）。
     */
    @PostMapping("/assets/{id}/enrich")
    public Result<Void> enrich(@PathVariable Long id,
                               @RequestParam(defaultValue = "true") boolean async) {
        if (async) {
            enrichmentService.enrichAsync(id);
        } else {
            enrichmentService.enrich(id);
        }
        return Result.ok();
    }

    private Long parseKeyId(String tokenId) {
        if (tokenId == null || !tokenId.startsWith(API_KEY_TOKEN_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(tokenId.substring(API_KEY_TOKEN_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
