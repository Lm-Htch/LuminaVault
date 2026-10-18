package com.lumi.vault.business.graph.dto;

import com.lumi.vault.business.graph.config.GraphSettings;

public record GraphSettingsResponse(GraphSettings effective, GraphSettings defaults) {
}
