package com.lumi.vault.business.model.dto;

import java.util.List;

public record ModelEmbedding(List<Double> embedding, String model) {
}
