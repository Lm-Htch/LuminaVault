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
}
