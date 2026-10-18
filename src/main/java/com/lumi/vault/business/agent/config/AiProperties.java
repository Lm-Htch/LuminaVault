package com.lumi.vault.business.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "lumina.ai")
public record AiProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("OLLAMA") AiProvider provider,
        String baseUrl,
        String apiKey,
        String chatModel,
        String embeddingBaseUrl,
        String embeddingApiKey,
        String embeddingModel,
        @DefaultValue("true") boolean vision,
        @DefaultValue("true") boolean rerank,
        @DefaultValue("true") boolean analyzeQuery,
        @DefaultValue("12h") Duration queryAnalyzeTtl,
        @DefaultValue("true") boolean verify,
        @DefaultValue("6") @Min(1) @Max(16) int verifyBatchSize,
        @DefaultValue("3") @Min(1) @Max(8) int verifyBatchConcurrency,
        @DefaultValue("5m") Duration searchCacheTtl,
        @DefaultValue("10485760") @Min(1024) long maxImageBytes,
        @DefaultValue("30s") Duration connectTimeout,
        @DefaultValue("120s") Duration readTimeout) {

    public String resolvedBaseUrl() {
        return hasText(baseUrl) ? baseUrl : provider.baseUrl();
    }

    public String resolvedChatModel() {
        return hasText(chatModel) ? chatModel : provider.chatModel();
    }

    public String resolvedEmbeddingBaseUrl() {
        return hasText(embeddingBaseUrl) ? embeddingBaseUrl : resolvedBaseUrl();
    }

    public String resolvedEmbeddingApiKey() {
        return hasText(embeddingApiKey) ? embeddingApiKey : apiKey;
    }

    public String resolvedEmbeddingModel() {
        return hasText(embeddingModel) ? embeddingModel : provider.embeddingModel();
    }

    public boolean chatEnabled() {
        return enabled && hasText(resolvedBaseUrl()) && hasText(resolvedChatModel());
    }

    public boolean visionEnabled() {
        return chatEnabled() && vision && (provider.supportsVision() || hasText(chatModel));
    }

    public boolean embeddingEnabled() {
        return enabled
                && hasText(resolvedEmbeddingModel())
                && hasText(resolvedEmbeddingBaseUrl())
                && (provider.supportsEmbedding() || hasText(embeddingModel));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
