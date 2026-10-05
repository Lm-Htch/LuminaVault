package com.lumi.vault.business.model.client;

import com.lumi.vault.business.model.config.ModelProperties;
import com.lumi.vault.business.model.dto.ModelAnalysis;
import com.lumi.vault.business.model.dto.ModelTag;
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
        return new ModelAnalysis(tags, characters, embedding, model);
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
