package com.lumi.vault.business.character.dto;

import com.lumi.vault.business.character.entity.Character;

import java.time.Instant;
import java.util.List;

public record CharacterResponse(
        Long id,
        String name,
        List<String> aliases,
        String series,
        String description,
        boolean autoCreated,
        long referenceCount,
        Instant createdAt) {

    public static CharacterResponse of(Character character, List<String> aliases, long referenceCount) {
        return new CharacterResponse(
                character.getId(),
                character.getName(),
                aliases,
                character.getSeries(),
                character.getDescription(),
                Boolean.TRUE.equals(character.getAutoCreated()),
                referenceCount,
                character.getCreatedAt());
    }
}
