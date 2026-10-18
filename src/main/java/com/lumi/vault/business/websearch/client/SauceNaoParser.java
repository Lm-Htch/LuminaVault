package com.lumi.vault.business.websearch.client;

import com.lumi.vault.business.websearch.dto.WebSourceResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * SauceNAO 响应解析（纯函数，便于单测）。
 */
@Component
public class SauceNaoParser {

    private final ObjectMapper objectMapper;

    public SauceNaoParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WebSourceResult parse(String json, String provider, double minSimilarity) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new WebSourceException("SauceNAO 响应解析失败", false);
        }
        JsonNode header = root.path("header");
        int status = header.path("status").asInt(0);
        if (status < 0) {
            boolean quota = status == -2;
            throw new WebSourceException("SauceNAO 返回状态 " + status
                    + (quota ? "（超出频率/配额限制）" : ""), quota);
        }
        for (JsonNode item : root.path("results")) {
            double similarity = parseSimilarity(item.path("header").path("similarity").asString("0"));
            if (similarity < minSimilarity) {
                break;
            }
            WebSourceResult result = toResult(item.path("data"), provider, similarity, json);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private WebSourceResult toResult(JsonNode data, String provider, double similarity, String raw) {
        String sourceType = null;
        String sourceUrl = null;
        String sourceId = null;
        long pixivId = data.path("pixiv_id").asLong(0);
        if (pixivId > 0) {
            sourceType = "pixiv";
            sourceId = String.valueOf(pixivId);
            sourceUrl = "https://www.pixiv.net/artworks/" + pixivId;
        } else if (data.path("ext_urls").isArray() && data.path("ext_urls").size() > 0) {
            sourceUrl = data.path("ext_urls").get(0).asString("");
            sourceType = hostType(sourceUrl);
        }
        String title = blankToNull(data.path("title").asString(""));
        String author = blankToNull(data.path("author_name").asString(""));
        if (author == null) {
            author = blankToNull(data.path("member_name").asString(""));
        }
        if (author == null && data.path("creator").isArray()) {
            for (JsonNode creator : data.path("creator")) {
                author = blankToNull(creator.asString(""));
                if (author != null) {
                    break;
                }
            }
        }
        List<String> characters = new ArrayList<>();
        JsonNode charactersNode = data.path("characters");
        if (charactersNode.isArray()) {
            for (JsonNode character : charactersNode) {
                String value = character.asString("").trim();
                if (!value.isEmpty()) {
                    characters.add(value);
                }
            }
        } else if (charactersNode.isTextual()) {
            for (String value : charactersNode.asString("").split(",")) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    characters.add(trimmed);
                }
            }
        }
        if (sourceUrl == null && sourceType == null && title == null && author == null && characters.isEmpty()) {
            return null;
        }
        String rawValue = raw.length() <= 20000 ? raw : raw.substring(0, 20000);
        return new WebSourceResult(provider, similarity, sourceType, sourceUrl, sourceId,
                title, author, List.copyOf(characters), rawValue);
    }

    private double parseSimilarity(String value) {
        try {
            return Double.parseDouble(value.trim()) / 100.0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String hostType(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return "web";
            }
            String lower = host.toLowerCase();
            if (lower.contains("pixiv")) {
                return "pixiv";
            }
            if (lower.contains("twitter") || lower.contains("x.com")) {
                return "twitter";
            }
            if (lower.contains("danbooru")) {
                return "danbooru";
            }
            if (lower.contains("yande.re")) {
                return "yandere";
            }
            if (lower.contains("gelbooru")) {
                return "gelbooru";
            }
            if (lower.contains("deviantart")) {
                return "deviantart";
            }
            if (lower.contains("artstation")) {
                return "artstation";
            }
            if (lower.contains("sankaku")) {
                return "sankaku";
            }
            return "web";
        } catch (Exception e) {
            return "web";
        }
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
