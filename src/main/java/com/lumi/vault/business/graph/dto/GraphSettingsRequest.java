package com.lumi.vault.business.graph.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.lumi.vault.business.graph.config.GraphSettings;

/**
 * 相似图参数更新请求：允许部分字段（null = 保持当前值）。
 */
public record GraphSettingsRequest(
        Boolean enabled,
        @DecimalMin("0.0") @DecimalMax("1.0") Double edgeThreshold,
        @Min(1) @Max(100) Integer maxEdgesPerAsset,
        @Min(1) @Max(200) Integer fetchPerChannel,
        @DecimalMin("0.0") @DecimalMax("1.0") Double wd14Weight,
        @DecimalMin("0.0") @DecimalMax("1.0") Double clipWeight,
        @DecimalMin("0.0") @DecimalMax("1.0") Double singleChannelDiscount,
        @DecimalMin("0.0") @DecimalMax("1.0") Double duplicateThreshold,
        @DecimalMin("0.0") @DecimalMax("2.0") Double routeWeight,
        @Min(1) @Max(200) Integer routeSeedLimit,
        @Min(1) @Max(100) Integer routeNeighbors,
        @Min(1) @Max(100) Integer routeNearRank,
        @Min(1) @Max(100) Integer routeMidRank,
        @DecimalMin("0.0") @DecimalMax("1.0") Double routeDecayNear,
        @DecimalMin("0.0") @DecimalMax("1.0") Double routeDecayMid,
        @DecimalMin("0.0") @DecimalMax("1.0") Double routeDecayFar,
        @DecimalMin("0.0") @DecimalMax("1.0") Double feedbackOneHopFactor,
        @DecimalMin("0.0") @DecimalMax("1.0") Double feedbackTwoHopFactor,
        @DecimalMin("0.0") @DecimalMax("1.0") Double feedbackEdgeThreshold,
        @Min(1) @Max(100) Integer feedbackNeighborLimit,
        @Min(1) @Max(100) Integer feedbackSecondHopLimit) {

    public GraphSettings merge(GraphSettings current) {
        return new GraphSettings(
                enabled == null ? current.enabled() : enabled,
                edgeThreshold == null ? current.edgeThreshold() : edgeThreshold,
                maxEdgesPerAsset == null ? current.maxEdgesPerAsset() : maxEdgesPerAsset,
                fetchPerChannel == null ? current.fetchPerChannel() : fetchPerChannel,
                wd14Weight == null ? current.wd14Weight() : wd14Weight,
                clipWeight == null ? current.clipWeight() : clipWeight,
                singleChannelDiscount == null ? current.singleChannelDiscount() : singleChannelDiscount,
                duplicateThreshold == null ? current.duplicateThreshold() : duplicateThreshold,
                routeWeight == null ? current.routeWeight() : routeWeight,
                routeSeedLimit == null ? current.routeSeedLimit() : routeSeedLimit,
                routeNeighbors == null ? current.routeNeighbors() : routeNeighbors,
                routeNearRank == null ? current.routeNearRank() : routeNearRank,
                routeMidRank == null ? current.routeMidRank() : routeMidRank,
                routeDecayNear == null ? current.routeDecayNear() : routeDecayNear,
                routeDecayMid == null ? current.routeDecayMid() : routeDecayMid,
                routeDecayFar == null ? current.routeDecayFar() : routeDecayFar,
                feedbackOneHopFactor == null ? current.feedbackOneHopFactor() : feedbackOneHopFactor,
                feedbackTwoHopFactor == null ? current.feedbackTwoHopFactor() : feedbackTwoHopFactor,
                feedbackEdgeThreshold == null ? current.feedbackEdgeThreshold() : feedbackEdgeThreshold,
                feedbackNeighborLimit == null ? current.feedbackNeighborLimit() : feedbackNeighborLimit,
                feedbackSecondHopLimit == null ? current.feedbackSecondHopLimit() : feedbackSecondHopLimit);
    }
}
