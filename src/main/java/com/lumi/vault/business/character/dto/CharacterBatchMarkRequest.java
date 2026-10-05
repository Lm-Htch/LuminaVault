package com.lumi.vault.business.character.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CharacterBatchMarkRequest(
        @NotEmpty(message = "assetIds 不能为空") List<Long> assetIds,
        @NotNull(message = "characterId 不能为空") Long characterId) {
}
