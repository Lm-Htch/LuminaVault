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

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public ModelAnalysis analyze(byte[] image, String contentType) {
        return new ModelAnalysis(tags, characters, embedding, model);
    }
}
