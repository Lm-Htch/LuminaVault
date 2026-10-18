package com.lumi.vault.business.graph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 相似图默认参数（可在 application.yaml / 环境变量调整，运行时可用前端设置覆盖）。
 */
@Validated
@ConfigurationProperties(prefix = "lumina.graph")
public record GraphProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0.72") double edgeThreshold,
        @DefaultValue("10") int maxEdgesPerAsset,
        @DefaultValue("12") int fetchPerChannel,
        @DefaultValue("0.6") double wd14Weight,
        @DefaultValue("0.4") double clipWeight,
        @DefaultValue("0.9") double singleChannelDiscount,
        @DefaultValue("0.9") double duplicateThreshold,
        @DefaultValue("0.8") double routeWeight,
        @DefaultValue("20") int routeSeedLimit,
        @DefaultValue("8") int routeNeighbors,
        @DefaultValue("5") int routeNearRank,
        @DefaultValue("12") int routeMidRank,
        @DefaultValue("1.0") double routeDecayNear,
        @DefaultValue("0.6") double routeDecayMid,
        @DefaultValue("0.3") double routeDecayFar,
        @DefaultValue("0.3") double feedbackOneHopFactor,
        @DefaultValue("0.1") double feedbackTwoHopFactor,
        @DefaultValue("0.8") double feedbackEdgeThreshold,
        @DefaultValue("10") int feedbackNeighborLimit,
        @DefaultValue("5") int feedbackSecondHopLimit) {
}
