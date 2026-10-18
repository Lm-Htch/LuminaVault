package com.lumi.vault.business.agent.dto;

import com.lumi.vault.business.perception.PerceptionProfile;

import java.util.List;

public record AssetAnalysisResponse(
        Long assetId,
        String description,
        List<String> tags,
        String status,
        String model,
        String error,
        Perception perception) {

    /** 感性特征（特征聚合结果）；无画像时为 null。 */
    public record Perception(List<String> mood, List<String> style, List<String> tone,
                             List<String> scene, List<String> usability, double wallpaperScore) {

        public static Perception from(PerceptionProfile profile) {
            if (profile == null || (profile.isEmpty() && profile.wallpaperScore() <= 0)) {
                return null;
            }
            return new Perception(profile.mood(), profile.style(), profile.tone(), profile.scene(),
                    profile.usability(), Math.round(profile.wallpaperScore() * 100) / 100.0);
        }
    }
}
