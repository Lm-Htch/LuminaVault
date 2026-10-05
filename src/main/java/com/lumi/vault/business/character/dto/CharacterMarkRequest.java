package com.lumi.vault.business.character.dto;

import jakarta.validation.constraints.NotNull;

public record CharacterMarkRequest(@NotNull(message = "characterId 不能为空") Long characterId) {
}
