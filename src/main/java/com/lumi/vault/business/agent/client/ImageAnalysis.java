package com.lumi.vault.business.agent.client;

import java.util.List;

/**
 * 视觉分析结果：描述、标签，以及感性特征（氛围/风格/色调/场景/用途）。
 */
public record ImageAnalysis(
        String description,
        List<String> tags,
        List<String> mood,
        List<String> style,
        List<String> tone,
        List<String> scene,
        List<String> usability) {

    public static ImageAnalysis of(String description, List<String> tags) {
        return new ImageAnalysis(description, tags, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean hasPerception() {
        return !mood.isEmpty() || !style.isEmpty() || !tone.isEmpty() || !scene.isEmpty() || !usability.isEmpty();
    }
}
