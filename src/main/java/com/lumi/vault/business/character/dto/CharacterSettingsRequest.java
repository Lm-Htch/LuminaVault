package com.lumi.vault.business.character.dto;

import jakarta.validation.constraints.NotNull;

public record CharacterSettingsRequest(@NotNull(message = "autoCreate 不能为空") Boolean autoCreate) {
}
