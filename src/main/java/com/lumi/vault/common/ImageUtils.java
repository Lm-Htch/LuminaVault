package com.lumi.vault.common;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public final class ImageUtils {

    private ImageUtils() {
    }

    /**
     * 将图片缩放到最长边不超过 maxSide 并编码为 JPEG；失败返回 null。
     */
    public static byte[] downscaleJpeg(byte[] image, int maxSide) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(image);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BufferedImage source = ImageIO.read(input);
            if (source == null) {
                return null;
            }
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            double scale = Math.min(1.0, maxSide / (double) Math.max(width, height));
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            graphics.dispose();
            return ImageIO.write(resized, "jpg", output) ? output.toByteArray() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按魔术字节探测图片真实 MIME；无法识别时返回 fallback。
     * 用于修正外部来源（浏览器按扩展名声明、重命名文件）的类型与内容不符，
     * 避免模型接口报 "declared media type does not match its content"。
     */
    public static String detectContentType(byte[] bytes, String fallback) {
        if (bytes == null || bytes.length < 4) {
            return fallback;
        }
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        int b2 = bytes[2] & 0xFF;
        int b3 = bytes[3] & 0xFF;
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
            return "image/png";
        }
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return "image/jpeg";
        }
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) {
            return "image/gif";
        }
        if (b0 == 0x42 && b1 == 0x4D) {
            return "image/bmp";
        }
        if (bytes.length >= 12
                && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && (bytes[8] & 0xFF) == 0x57 && (bytes[9] & 0xFF) == 0x45
                && (bytes[10] & 0xFF) == 0x42 && (bytes[11] & 0xFF) == 0x50) {
            return "image/webp";
        }
        if (bytes.length >= 12
                && (bytes[4] & 0xFF) == 0x66 && (bytes[5] & 0xFF) == 0x74
                && (bytes[6] & 0xFF) == 0x79 && (bytes[7] & 0xFF) == 0x70) {
            return "image/avif";
        }
        return fallback;
    }
}
