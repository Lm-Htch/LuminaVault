package com.lumi.vault.business.character.controller;

import com.lumi.vault.business.character.dto.CharacterBatchMarkRequest;
import com.lumi.vault.business.character.dto.CharacterMarkRequest;
import com.lumi.vault.business.character.dto.CharacterReferenceRequest;
import com.lumi.vault.business.character.dto.CharacterRequest;
import com.lumi.vault.business.character.dto.CharacterResponse;
import com.lumi.vault.business.character.dto.CharacterSettingsRequest;
import com.lumi.vault.business.character.dto.CharacterSettingsResponse;
import com.lumi.vault.business.character.service.CharacterService;
import com.lumi.vault.business.character.service.CharacterSettingsService;
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
@RequestMapping("/api/characters")
public class CharacterController {

    private final CharacterService characterService;
    private final CharacterSettingsService settingsService;

    public CharacterController(CharacterService characterService, CharacterSettingsService settingsService) {
        this.characterService = characterService;
        this.settingsService = settingsService;
    }

    @GetMapping
    public Result<List<CharacterResponse>> list() {
        return Result.ok(characterService.list());
    }

    @PostMapping
    public Result<CharacterResponse> create(@Valid @RequestBody CharacterRequest request) {
        return Result.ok(characterService.create(request));
    }

    @PutMapping("/{id}")
    public Result<CharacterResponse> update(@PathVariable Long id, @Valid @RequestBody CharacterRequest request) {
        return Result.ok(characterService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        characterService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/references")
    public Result<Void> addReference(@PathVariable Long id, @Valid @RequestBody CharacterReferenceRequest request) {
        characterService.addReference(id, request.assetId());
        return Result.ok();
    }

    @GetMapping("/settings")
    public Result<CharacterSettingsResponse> settings() {
        return Result.ok(new CharacterSettingsResponse(settingsService.autoCreate()));
    }

    @PutMapping("/settings")
    public Result<CharacterSettingsResponse> updateSettings(@Valid @RequestBody CharacterSettingsRequest request) {
        return Result.ok(new CharacterSettingsResponse(settingsService.updateAutoCreate(request.autoCreate())));
    }

    @PostMapping("/bootstrap")
    public Result<Integer> bootstrap() {
        return Result.ok(characterService.bootstrapFromTags());
    }

    @PutMapping("/assets")
    public Result<Integer> markBatch(@Valid @RequestBody CharacterBatchMarkRequest request) {
        return Result.ok(characterService.markBatch(request.assetIds(), request.characterId()));
    }

    @PutMapping("/assets/{assetId}")
    public Result<Void> markAsset(@PathVariable Long assetId, @Valid @RequestBody CharacterMarkRequest request) {
        characterService.markAsset(assetId, request.characterId());
        return Result.ok();
    }

    @DeleteMapping("/assets/{assetId}")
    public Result<Void> unmarkAsset(@PathVariable Long assetId) {
        characterService.unmarkAsset(assetId);
        return Result.ok();
    }
}
