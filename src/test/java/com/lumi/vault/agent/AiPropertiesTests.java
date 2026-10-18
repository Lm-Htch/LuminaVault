package com.lumi.vault.agent;

import com.lumi.vault.business.agent.config.AiProperties;
import com.lumi.vault.business.agent.config.AiProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AiPropertiesTests {

    @Test
    void resolvesOllamaPreset() {
        AiProperties properties = properties(AiProvider.OLLAMA, null, null, null, null);
        assertThat(properties.resolvedBaseUrl()).isEqualTo("http://localhost:11434/v1");
        assertThat(properties.resolvedChatModel()).isEqualTo("qwen2.5vl:7b");
        assertThat(properties.resolvedEmbeddingModel()).isEqualTo("nomic-embed-text");
        assertThat(properties.chatEnabled()).isTrue();
        assertThat(properties.visionEnabled()).isTrue();
        assertThat(properties.embeddingEnabled()).isTrue();
    }

    @Test
    void resolvesDeepSeekAsTextOnly() {
        AiProperties properties = properties(AiProvider.DEEPSEEK, null, null, null, null);
        assertThat(properties.resolvedBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(properties.resolvedChatModel()).isEqualTo("deepseek-chat");
        assertThat(properties.chatEnabled()).isTrue();
        assertThat(properties.visionEnabled()).isFalse();
        assertThat(properties.embeddingEnabled()).isFalse();
    }

    @Test
    void resolvesOpenAiPreset() {
        AiProperties properties = properties(AiProvider.OPENAI, null, null, null, null);
        assertThat(properties.resolvedBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(properties.resolvedChatModel()).isEqualTo("gpt-4o-mini");
        assertThat(properties.resolvedEmbeddingModel()).isEqualTo("text-embedding-3-small");
        assertThat(properties.visionEnabled()).isTrue();
        assertThat(properties.embeddingEnabled()).isTrue();
    }

    @Test
    void explicitConfigOverridesPreset() {
        AiProperties properties = properties(AiProvider.DEEPSEEK,
                "http://localhost:11434/v1", "gpt-4o", null, "text-embedding-3-small");
        assertThat(properties.resolvedBaseUrl()).isEqualTo("http://localhost:11434/v1");
        assertThat(properties.resolvedChatModel()).isEqualTo("gpt-4o");
        assertThat(properties.resolvedEmbeddingBaseUrl()).isEqualTo("http://localhost:11434/v1");
        assertThat(properties.embeddingEnabled()).isTrue();
    }

    @Test
    void disabledByDefault() {
        AiProperties properties = new AiProperties(false, AiProvider.OPENAI,
                null, null, null, null, null, null, true, true, true, Duration.ofHours(12), true, 6, 3,
                Duration.ofMinutes(5), 10485760,
                Duration.ofSeconds(30), Duration.ofSeconds(120));
        assertThat(properties.chatEnabled()).isFalse();
        assertThat(properties.visionEnabled()).isFalse();
        assertThat(properties.embeddingEnabled()).isFalse();
    }

    private AiProperties properties(AiProvider provider, String baseUrl, String chatModel,
                                    String embeddingBaseUrl, String embeddingModel) {
        return new AiProperties(true, provider, baseUrl, "test-key", chatModel,
                embeddingBaseUrl, null, embeddingModel, true, true, true, Duration.ofHours(12), true, 6, 3,
                Duration.ofMinutes(5), 10485760,
                Duration.ofSeconds(30), Duration.ofSeconds(120));
    }
}
