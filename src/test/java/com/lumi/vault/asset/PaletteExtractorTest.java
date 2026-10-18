package com.lumi.vault.asset;

import com.lumi.vault.business.asset.service.PaletteExtractor;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class PaletteExtractorTest {

    @Test
    void namesSolidColors() {
        assertThat(PaletteExtractor.dominantColors(solid(Color.RED), 5)).contains("red");
        assertThat(PaletteExtractor.dominantColors(solid(new Color(60, 90, 220)), 5)).contains("blue");
        assertThat(PaletteExtractor.dominantColors(solid(Color.WHITE), 5)).contains("white");
    }

    @Test
    void picksMultipleDominantColors() {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 20, 40);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(20, 0, 20, 40);
        graphics.dispose();

        assertThat(PaletteExtractor.dominantColors(image, 5)).contains("red", "blue");
    }

    private BufferedImage solid(Color color) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 32, 32);
        graphics.dispose();
        return image;
    }
}
