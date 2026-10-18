package com.lumi.vault.business.model.dto;

import java.util.List;

public record ModelAnalysis(List<ModelTag> tags, List<String> characters, List<Double> embedding, String model,
                            String ocrText) {
}
