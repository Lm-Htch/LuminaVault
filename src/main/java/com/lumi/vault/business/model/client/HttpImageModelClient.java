package com.lumi.vault.business.model.client;

import com.lumi.vault.business.model.config.ModelProperties;
import com.lumi.vault.business.model.dto.ModelAnalysis;
import com.lumi.vault.business.model.dto.ModelTag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class HttpImageModelClient implements ImageModelClient {

    private final ModelProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public HttpImageModelClient(ModelProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        String baseUrl = properties.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        }
        this.restClient = builder.build();
    }

    @Override
    public boolean available() {
        return properties.enabled();
    }

    @Override
    public ModelAnalysis analyze(byte[] image, String contentType) {
        Map<String, Object> body = Map.of(
                "image", Base64.getEncoder().encodeToString(image),
                "content_type", contentType == null ? "image/jpeg" : contentType);
        JsonNode root = readTree(post("/v1/analyze", body));
        List<ModelTag> tags = new ArrayList<>();
        for (JsonNode node : root.path("tags")) {
            double confidence = node.path("confidence").asDouble(0);
            String tag = node.path("tag").asString("");
            if (!tag.isBlank() && confidence >= properties.tagConfidence()) {
                tags.add(new ModelTag(tag, confidence));
            }
        }
        List<String> characters = new ArrayList<>();
        for (JsonNode node : root.path("characters")) {
            String character = node.asString("");
            if (!character.isBlank()) {
                characters.add(character);
            }
        }
        List<Double> embedding = new ArrayList<>();
        for (JsonNode value : root.path("embedding")) {
            embedding.add(value.asDouble());
        }
        String model = root.path("model").asString("external-image");
        String ocrText = root.path("ocr_text").asString("");
        return new ModelAnalysis(tags, characters, embedding, model, ocrText.isBlank() ? null : ocrText);
    }

    @Override
    public List<Double> rerank(String query, List<String> documents) {
        if (!available() || documents == null || documents.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> body = Map.of("query", query, "documents", documents);
            JsonNode root = readTree(post("/v1/rerank", body));
            List<Double> scores = new ArrayList<>();
            for (JsonNode value : root.path("scores")) {
                scores.add(value.asDouble());
            }
            return scores.size() == documents.size() ? scores : null;
        } catch (Exception e) {
            log.debug("本地重排不可用，回退: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<Double> embed(byte[] image, String contentType) {
        return embedFrom("/v1/embed", image, contentType);
    }

    @Override
    public List<Double> clipEmbed(byte[] image, String contentType) {
        return embedFrom("/v1/clip-embed", image, contentType);
    }

    @Override
    public List<Double> clipTextEmbed(String text) {
        if (!available() || text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode root = readTree(post("/v1/clip-text-embed", Map.of("text", text)));
            List<Double> embedding = new ArrayList<>();
            for (JsonNode value : root.path("embedding")) {
                embedding.add(value.asDouble());
            }
            return embedding.isEmpty() ? null : embedding;
        } catch (Exception e) {
            log.debug("CLIP 文本向量化失败: {}", e.getMessage());
            return null;
        }
    }

    private List<Double> embedFrom(String path, byte[] image, String contentType) {
        if (!available() || image == null || image.length == 0) {
            return null;
        }
        try {
            Map<String, Object> body = Map.of(
                    "image", Base64.getEncoder().encodeToString(image),
                    "content_type", contentType == null ? "image/jpeg" : contentType);
            JsonNode root = readTree(post(path, body));
            List<Double> embedding = new ArrayList<>();
            for (JsonNode value : root.path("embedding")) {
                embedding.add(value.asDouble());
            }
            return embedding.isEmpty() ? null : embedding;
        } catch (Exception e) {
            log.warn("图片向量化失败（{}）: {}", path, e.getMessage());
            return null;
        }
    }

    private String post(String path, Map<String, Object> body) {
        try {
            byte[] bytes = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("模型服务返回 " + e.getStatusCode().value() + ": "
                    + truncate(e.getResponseBodyAsString()));
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("解析模型服务响应失败", e);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300);
    }
}
