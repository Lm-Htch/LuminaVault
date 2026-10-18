package com.lumi.vault.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUtilsContentTypeTest {

    @Test
    void detectsRealTypeRegardlessOfDeclaredOne() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] gif = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0};
        byte[] bmp = {0x42, 0x4D, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] webp = {0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50};
        byte[] avif = {0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70, 0x61, 0x76, 0x69, 0x66};

        assertThat(ImageUtils.detectContentType(png, "image/jpeg")).isEqualTo("image/png");
        assertThat(ImageUtils.detectContentType(jpeg, "image/png")).isEqualTo("image/jpeg");
        assertThat(ImageUtils.detectContentType(gif, "image/jpeg")).isEqualTo("image/gif");
        assertThat(ImageUtils.detectContentType(bmp, "image/jpeg")).isEqualTo("image/bmp");
        assertThat(ImageUtils.detectContentType(webp, "image/jpeg")).isEqualTo("image/webp");
        assertThat(ImageUtils.detectContentType(avif, "image/jpeg")).isEqualTo("image/avif");
    }

    @Test
    void fallsBackWhenUnknownOrTooShort() {
        assertThat(ImageUtils.detectContentType(null, "image/jpeg")).isEqualTo("image/jpeg");
        assertThat(ImageUtils.detectContentType(new byte[]{1, 2}, "image/png")).isEqualTo("image/png");
        assertThat(ImageUtils.detectContentType(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, "image/webp")).isEqualTo("image/webp");
    }
}
