package com.lumi.vault.business.character.dto;

import jakarta.validation.constraints.NotNull;

public record CharacterReferenceRequest(@NotNull(message = "assetId 不能为空") Long assetId) {
}
