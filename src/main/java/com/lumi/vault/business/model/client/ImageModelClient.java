package com.lumi.vault.business.model.client;

import com.lumi.vault.business.model.dto.ModelAnalysis;

import java.util.List;

public interface ImageModelClient {

    boolean available();

    ModelAnalysis analyze(byte[] image, String contentType);

    /**
     * 本地 cross-encoder 精排；不可用时返回 null，调用方回退。
     */
    default List<Double> rerank(String query, List<String> documents) {
        return null;
    }

    /**
     * 计算图片向量（以图搜图）；不可用或失败返回 null。
     */
    default List<Double> embed(byte[] image, String contentType) {
        return null;
    }

    /**
     * 计算通用 CLIP 图片向量；不可用或失败返回 null。
     */
    default List<Double> clipEmbed(byte[] image, String contentType) {
        return null;
    }

    /**
     * 计算 CLIP 文本向量（感性文本检索）；不可用或失败返回 null。
     */
    default List<Double> clipTextEmbed(String text) {
        return null;
    }
}
