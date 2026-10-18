package com.lumi.vault.business.agent.client;

import com.lumi.vault.business.agent.config.AiProperties;
import com.lumi.vault.common.ImageUtils;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class OpenAiClient implements AiClient {

    private static final String VISION_SYSTEM_PROMPT = """
            你是图片资产管理助手。请观察图片并输出 JSON，不要输出其他内容：
            {"description":"一句简洁的中文描述","tags":["标签1","标签2"],"mood":["氛围词"],"style":["风格词"],
             "tone":["色调词"],"scene":["场景词"],"usability":["用途"]}
            规则：
            - tags 为 3-8 个中文关键词，覆盖主体、场景、风格、颜色等
            - mood 为 2-4 个氛围/情绪词，例如：温馨/治愈/宁静/梦幻/唯美/浪漫/可爱/清新/热烈/孤独/悲伤/忧郁/暗黑/氛围感
            - style 为 2-4 个风格词，例如：水彩/手绘/油画/印象派/素描/线稿/平涂/写实/像素/3D/极简/Q版/厚涂/插画
            - tone 为 1-3 个色调词，例如：暖色/冷色/明亮/暗调/单色/灰度/粉彩/低饱和/鲜艳/柔和
            - scene 为 1-4 个场景词，例如：全景风景/星空/夜晚/城市/室内/户外/海滩/海洋/森林/天空/樱花/黄昏/雪景/水下/人物特写
            - usability 为 0-2 个用途词，例如：适合壁纸/适合头像/适合横屏/适合竖屏；没有把握时给空数组
            - 只输出 JSON""";

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
        long started = System.nanoTime();
        String dataUrl = "data:" + ImageUtils.detectContentType(image, contentType == null ? "image/jpeg" : contentType)
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
        logUsage("vision-analyze", root, started);
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
        long started = System.nanoTime();
        Map<String, Object> body = Map.of(
                "model", properties.resolvedChatModel(),
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        JsonNode root = readTree(post(chatRestClient, "/chat/completions", body, properties.apiKey()));
        logUsage("chat", root, started);
        return root.path("choices").path(0).path("message").path("content").asString("");
    }

    @Override
    public String completeWithImages(String systemPrompt, String userPrompt, List<VisionImage> images) {
        require(visionEnabled(), "当前 LLM 未启用图片理解");
        long started = System.nanoTime();
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", userPrompt));
        for (VisionImage image : images) {
            String dataUrl = "data:" + ImageUtils.detectContentType(image.data(),
                    image.contentType() == null ? "image/jpeg" : image.contentType())
                    + ";base64," + Base64.getEncoder().encodeToString(image.data());
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        }
        Map<String, Object> body = Map.of(
                "model", properties.resolvedChatModel(),
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", content)));
        JsonNode root = readTree(post(chatRestClient, "/chat/completions", body, properties.apiKey()));
        logUsage("vision-verify", root, started);
        return root.path("choices").path(0).path("message").path("content").asString("");
    }

    /** 记录每次远程 LLM 调用的 token 用量与耗时（OpenAI 兼容响应含 usage 字段）。 */
    private void logUsage(String tag, JsonNode root, long startedNanos) {
        long ms = (System.nanoTime() - startedNanos) / 1_000_000;
        JsonNode usage = root.path("usage");
        long prompt = usage.path("prompt_tokens").asLong(-1);
        long completion = usage.path("completion_tokens").asLong(-1);
        long total = usage.path("total_tokens").asLong(prompt < 0 || completion < 0 ? -1 : prompt + completion);
        if (total >= 0) {
            log.info("LLM usage [{}] model={} prompt={} completion={} total={} elapsedMs={}",
                    tag, properties.resolvedChatModel(), prompt, completion, total, ms);
        } else {
            log.info("LLM usage [{}] model={}（响应未含 usage）elapsedMs={}",
                    tag, properties.resolvedChatModel(), ms);
        }
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
            return new ImageAnalysis(description, stringList(node, "tags"), stringList(node, "mood"),
                    stringList(node, "style"), stringList(node, "tone"), stringList(node, "scene"),
                    stringList(node, "usability"));
        } catch (Exception e) {
            return ImageAnalysis.of(cleaned.trim(), List.of());
        }
    }

    private List<String> stringList(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node.path(field)) {
            String value = item.asString("").trim();
            if (!value.isEmpty() && !values.contains(value) && values.size() < 12) {
                values.add(value);
            }
        }
        return values;
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
