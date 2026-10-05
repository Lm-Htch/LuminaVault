package com.lumi.vault.business.agent.client;

import com.lumi.vault.business.agent.config.AiProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiClient implements AiClient {

    private static final String VISION_SYSTEM_PROMPT = """
            你是图片资产管理助手。请观察图片并输出 JSON，不要输出其他内容：
            {"description":"一句简洁的中文描述","tags":["标签1","标签2"]}
            标签为 3-8 个中文关键词，覆盖主体、场景、风格、颜色等。""";

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient chatRestClient;
    private final RestClient embeddingRestClient;

    public OpenAiClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.chatRestClient = buildClient(properties.resolvedBaseUrl(), requestFactory);
        this.embeddingRestClient = buildClient(properties.resolvedEmbeddingBaseUrl(), requestFactory);
    }

    @Override
    public boolean available() {
        return properties.enabled();
    }

    @Override
    public boolean visionEnabled() {
        return properties.visionEnabled();
    }

    @Override
    public boolean embeddingEnabled() {
        return properties.embeddingEnabled();
    }

    @Override
    public boolean chatEnabled() {
        return properties.chatEnabled();
    }

    @Override
    public ImageAnalysis analyzeImage(byte[] image, String contentType) {
        require(visionEnabled(), "当前 LLM 未启用图片理解");
        String dataUrl = "data:" + (contentType == null ? "image/jpeg" : contentType)
                + ";base64," + Base64.getEncoder().encodeToString(image);
        Map<String, Object> body = Map.of(
                "model", properties.resolvedChatModel(),
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", VISION_SYSTEM_PROMPT),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", "请分析这张图片。"),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))))));
        JsonNode root = readTree(post(chatRestClient, "/chat/completions", body, properties.apiKey()));
        String content = root.path("choices").path(0).path("message").path("content").asString("");
        return parseAnalysis(content);
    }

    @Override
    public List<Double> embed(String text) {
        require(embeddingEnabled(), "当前 LLM 未启用向量化");
        Map<String, Object> body = Map.of(
                "model", properties.resolvedEmbeddingModel(),
                "input", List.of(text));
        JsonNode data = readTree(post(embeddingRestClient, "/embeddings", body, properties.resolvedEmbeddingApiKey()))
                .path("data");
        JsonNode embedding = data.path(0).path("embedding");
        List<Double> vector = new ArrayList<>();
        for (JsonNode value : embedding) {
            vector.add(value.asDouble());
        }
        if (vector.isEmpty()) {
            throw new IllegalStateException("模型未返回 embedding");
        }
        return vector;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        require(chatEnabled(), "当前 LLM 未启用对话");
        Map<String, Object> body = Map.of(
                "model", properties.resolvedChatModel(),
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        JsonNode root = readTree(post(chatRestClient, "/chat/completions", body, properties.apiKey()));
        return root.path("choices").path(0).path("message").path("content").asString("");
    }

    private RestClient buildClient(String baseUrl, SimpleClientHttpRequestFactory requestFactory) {
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        }
        return builder.build();
    }

    private String post(RestClient client, String path, Map<String, Object> body, String apiKey) {
        RestClient.RequestBodySpec request = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(apiKey)) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        try {
            byte[] bytes = request.body(body).retrieve().body(byte[].class);
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("模型接口返回 " + e.getStatusCode().value() + ": "
                    + truncate(e.getResponseBodyAsString()));
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("解析模型响应失败", e);
        }
    }

    private ImageAnalysis parseAnalysis(String content) {
        String cleaned = stripCodeFence(content);
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            String description = node.path("description").asString("");
            List<String> tags = new ArrayList<>();
            for (JsonNode tag : node.path("tags")) {
                tags.add(tag.asString());
            }
            return new ImageAnalysis(description, tags);
        } catch (Exception e) {
            return new ImageAnalysis(cleaned.trim(), List.of());
        }
    }

    private String stripCodeFence(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.trim();
        if (cleaned.startsWith("```")) {
            int firstLine = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                cleaned = cleaned.substring(firstLine + 1, lastFence).trim();
            }
        }
        return cleaned;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
