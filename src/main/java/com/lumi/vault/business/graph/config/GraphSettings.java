package com.lumi.vault.business.graph.config;

/**
 * 生效中的相似图参数（默认值 + 运行时覆盖后的结果）。
 */
public record GraphSettings(
        boolean enabled,
        double edgeThreshold,
        int maxEdgesPerAsset,
        int fetchPerChannel,
        double wd14Weight,
        double clipWeight,
        double singleChannelDiscount,
        double duplicateThreshold,
        double routeWeight,
        int routeSeedLimit,
        int routeNeighbors,
        int routeNearRank,
        int routeMidRank,
        double routeDecayNear,
        double routeDecayMid,
        double routeDecayFar,
        double feedbackOneHopFactor,
        double feedbackTwoHopFactor,
        double feedbackEdgeThreshold,
        int feedbackNeighborLimit,
        int feedbackSecondHopLimit) {
}
