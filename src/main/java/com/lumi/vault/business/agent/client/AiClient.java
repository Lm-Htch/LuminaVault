package com.lumi.vault.business.agent.client;

import java.util.List;

public interface AiClient {

    boolean available();

    boolean visionEnabled();

    boolean embeddingEnabled();

    boolean chatEnabled();

    ImageAnalysis analyzeImage(byte[] image, String contentType);

    List<Double> embed(String text);

    String complete(String systemPrompt, String userPrompt);

    /**
     * 多模态确认：附带若干图片（按顺序对应 prompt 中的候选编号）。不支持时返回 null。
     */
    default String completeWithImages(String systemPrompt, String userPrompt, List<VisionImage> images) {
        return null;
    }
}
