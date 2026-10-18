package com.lumi.vault.model;

import com.lumi.vault.business.model.client.ImageModelClient;
import com.lumi.vault.business.model.dto.ModelAnalysis;
import com.lumi.vault.business.model.dto.ModelTag;

import java.util.List;

public class FakeImageModelClient implements ImageModelClient {

    private boolean available = true;
    private List<ModelTag> tags = List.of();
    private List<String> characters = List.of();
    private List<Double> embedding = List.of(0.9, 0.2, 0.1);
    private String model = "fake-image";
    private String ocrText;
    private List<Double> rerankScores;
    private List<Double> clipEmbedding;
    private List<Double> clipTextEmbedding;

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void setTags(List<ModelTag> tags) {
        this.tags = tags;
    }

    public void setCharacters(List<String> characters) {
        this.characters = characters;
    }

    public void setEmbedding(List<Double> embedding) {
        this.embedding = embedding;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setOcrText(String ocrText) {
        this.ocrText = ocrText;
    }

    public void setRerankScores(List<Double> rerankScores) {
        this.rerankScores = rerankScores;
    }

    public void setClipEmbedding(List<Double> clipEmbedding) {
        this.clipEmbedding = clipEmbedding;
    }

    public void setClipTextEmbedding(List<Double> clipTextEmbedding) {
        this.clipTextEmbedding = clipTextEmbedding;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public ModelAnalysis analyze(byte[] image, String contentType) {
        return new ModelAnalysis(tags, characters, embedding, model, ocrText);
    }

    @Override
    public List<Double> rerank(String query, List<String> documents) {
        if (!available || rerankScores == null || rerankScores.size() != documents.size()) {
            return null;
        }
        return rerankScores;
    }

    @Override
    public List<Double> embed(byte[] image, String contentType) {
        if (!available || embedding == null || embedding.isEmpty()) {
            return null;
        }
        return embedding;
    }

    @Override
    public List<Double> clipEmbed(byte[] image, String contentType) {
        if (!available || clipEmbedding == null || clipEmbedding.isEmpty()) {
            return null;
        }
        return clipEmbedding;
    }

    @Override
    public List<Double> clipTextEmbed(String text) {
        if (!available || clipTextEmbedding == null || clipTextEmbedding.isEmpty()) {
            return null;
        }
        return clipTextEmbedding;
    }
}
