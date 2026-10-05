package com.lumi.vault.agent;

import com.lumi.vault.business.agent.client.AiClient;
import com.lumi.vault.business.agent.client.ImageAnalysis;

import java.util.List;

public class FakeAiClient implements AiClient {

    private boolean available = true;
    private boolean vision = true;
    private boolean embedding = true;
    private String completion = "{\"comment\":\"fake\",\"results\":[]}";

    public void setCompletion(String completion) {
        this.completion = completion;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void setVision(boolean vision) {
        this.vision = vision;
    }

    public void setEmbedding(boolean embedding) {
        this.embedding = embedding;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public boolean visionEnabled() {
        return available && vision;
    }

    @Override
    public boolean embeddingEnabled() {
        return available && embedding;
    }

    @Override
    public boolean chatEnabled() {
        return available;
    }

    @Override
    public ImageAnalysis analyzeImage(byte[] image, String contentType) {
        return new ImageAnalysis("测试图片描述", List.of("测试", "图片"));
    }

    @Override
    public List<Double> embed(String text) {
        double sea = text.contains("海") ? 1.0 : 0.1;
        double mountain = text.contains("山") ? 1.0 : 0.1;
        double length = Math.min(text.length(), 100) / 1000.0;
        return List.of(sea, mountain, length);
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return completion;
    }
}
