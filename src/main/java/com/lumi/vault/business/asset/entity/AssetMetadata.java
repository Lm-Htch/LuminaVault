package com.lumi.vault.business.asset.entity;

import java.util.Map;

public record AssetMetadata(
        String author,
        String copyright,
        String cameraMake,
        String cameraModel,
        String lensModel,
        String software,
        String dateTaken,
        Integer orientation,
        Double gpsLatitude,
        Double gpsLongitude,
        Map<String, String> attributes) {
}
