package com.lumi.vault.business.agent.util;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

public final class VectorUtils {

    private VectorUtils() {
    }

    public static String toJson(Object value, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    public static double[] fromJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray() || node.isEmpty()) {
                return null;
            }
            double[] vector = new double[node.size()];
            for (int i = 0; i < node.size(); i++) {
                vector[i] = node.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            return null;
        }
    }

    public static double[] toArray(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            return null;
        }
        double[] array = new double[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            array[i] = vector.get(i);
        }
        return array;
    }

    public static List<String> stringsFromJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> values = new java.util.ArrayList<>();
            for (JsonNode item : node) {
                values.add(item.asString());
            }
            return values;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
