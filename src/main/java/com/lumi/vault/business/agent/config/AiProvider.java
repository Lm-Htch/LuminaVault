package com.lumi.vault.business.agent.config;

public enum AiProvider {

    OLLAMA("http://localhost:11434/v1", "qwen2.5vl:7b", "nomic-embed-text", true, true),
    DEEPSEEK("https://api.deepseek.com/v1", "deepseek-chat", null, false, false),
    OPENAI("https://api.openai.com/v1", "gpt-4o-mini", "text-embedding-3-small", true, true),
    CUSTOM(null, null, null, true, true);

    private final String baseUrl;
    private final String chatModel;
    private final String embeddingModel;
    private final boolean vision;
    private final boolean embedding;

    AiProvider(String baseUrl, String chatModel, String embeddingModel, boolean vision, boolean embedding) {
        this.baseUrl = baseUrl;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.vision = vision;
        this.embedding = embedding;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String chatModel() {
        return chatModel;
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    public boolean supportsVision() {
        return vision;
    }

    public boolean supportsEmbedding() {
        return embedding;
    }
}
