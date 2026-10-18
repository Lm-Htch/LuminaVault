package com.lumi.vault.business.asset.service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主色板提取：下采样 + 4bit 分桶量化 + 最近锚点色命名。
 */
public final class PaletteExtractor {

    private static final List<Anchor> ANCHORS = List.of(
            new Anchor("white", 240, 240, 240),
            new Anchor("black", 30, 30, 30),
            new Anchor("gray", 128, 128, 128),
            new Anchor("red", 215, 40, 40),
            new Anchor("orange", 240, 140, 40),
            new Anchor("yellow", 240, 220, 60),
            new Anchor("green", 70, 170, 80),
            new Anchor("cyan", 60, 190, 200),
            new Anchor("blue", 60, 90, 220),
            new Anchor("purple", 140, 80, 200),
            new Anchor("pink", 240, 130, 180),
            new Anchor("brown", 140, 95, 60));

    private PaletteExtractor() {
    }

    private record Anchor(String name, int r, int g, int b) {
    }

    public static List<String> dominantColors(BufferedImage image, int maxColors) {
        if (image == null || maxColors <= 0) {
            return List.of();
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return List.of();
        }
        int step = Math.max(1, Math.min(width, height) / 64);
        Map<Integer, int[]> buckets = new HashMap<>();
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int rgb = image.getRGB(x, y);
                int alpha = (rgb >>> 24) & 0xFF;
                if (alpha < 64) {
                    continue;
                }
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
                int[] acc = buckets.computeIfAbsent(key, ignored -> new int[4]);
                acc[0]++;
                acc[1] += r;
                acc[2] += g;
                acc[3] += b;
            }
        }
        List<int[]> sorted = new ArrayList<>(buckets.values());
        sorted.sort(Comparator.comparingInt((int[] acc) -> acc[0]).reversed());
        List<String> names = new ArrayList<>();
        for (int[] acc : sorted) {
            if (names.size() >= maxColors) {
                break;
            }
            String name = nearestColorName(acc[1] / acc[0], acc[2] / acc[0], acc[3] / acc[0]);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    public static String dominantColorsString(BufferedImage image, int maxColors) {
        List<String> names = dominantColors(image, maxColors);
        return names.isEmpty() ? null : String.join(",", names);
    }

    public static String nearestColorName(int r, int g, int b) {
        String best = "gray";
        long bestDistance = Long.MAX_VALUE;
        for (Anchor anchor : ANCHORS) {
            long dr = r - anchor.r();
            long dg = g - anchor.g();
            long db = b - anchor.b();
            long distance = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = anchor.name();
            }
        }
        return best;
    }
}
