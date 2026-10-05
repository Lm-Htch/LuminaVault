package com.lumi.vault.business.asset.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.lumi.vault.business.asset.config.AssetProperties;
import com.lumi.vault.business.asset.entity.AssetMetadata;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ImageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessor.class);
    private static final int MAX_ATTRIBUTES = 200;

    private final AssetProperties properties;

    public ImageProcessor(AssetProperties properties) {
        this.properties = properties;
    }

    public record ProcessedImage(Integer width, Integer height, Path thumbnail,
                                 String thumbnailContentType, AssetMetadata metadata) {
    }

    public ProcessedImage process(Path source, String contentType) {
        AssetMetadata metadata = extractMetadata(source);
        Path thumbnail = null;
        String thumbnailContentType = null;
        Integer width = null;
        Integer height = null;
        try {
            BufferedImage image = ImageIO.read(source.toFile());
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
                int orientation = metadata != null && metadata.orientation() != null ? metadata.orientation() : 1;
                if (isRotated(orientation)) {
                    int originalWidth = width;
                    width = height;
                    height = originalWidth;
                }
                boolean png = "image/png".equalsIgnoreCase(contentType);
                thumbnailContentType = png ? "image/png" : "image/jpeg";
                thumbnail = Files.createTempFile("asset-thumb-", png ? ".png" : ".jpg");
                Thumbnails.of(source.toFile())
                        .useExifOrientation(true)
                        .size(properties.thumbnailMaxSize(), properties.thumbnailMaxSize())
                        .outputQuality(0.85)
                        .toFile(thumbnail.toFile());
            }
        } catch (Exception e) {
            log.warn("图片处理失败，跳过缩略图: {}", e.getMessage());
            if (thumbnail != null) {
                try {
                    Files.deleteIfExists(thumbnail);
                } catch (Exception ignored) {
                }
                thumbnail = null;
                thumbnailContentType = null;
            }
        }
        return new ProcessedImage(width, height, thumbnail, thumbnailContentType, metadata);
    }

    private AssetMetadata extractMetadata(Path source) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(source.toFile());
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);

            String author = description(ifd0, ExifDirectoryBase.TAG_ARTIST);
            String copyright = description(ifd0, ExifDirectoryBase.TAG_COPYRIGHT);
            String cameraMake = description(ifd0, ExifDirectoryBase.TAG_MAKE);
            String cameraModel = description(ifd0, ExifDirectoryBase.TAG_MODEL);
            String software = description(ifd0, ExifDirectoryBase.TAG_SOFTWARE);
            Integer orientation = integer(ifd0, ExifDirectoryBase.TAG_ORIENTATION);
            String dateTaken = description(subIfd, ExifDirectoryBase.TAG_DATETIME_ORIGINAL);
            String lensModel = description(subIfd, ExifDirectoryBase.TAG_LENS_MODEL);

            Double gpsLatitude = null;
            Double gpsLongitude = null;
            if (gps != null) {
                GeoLocation location = gps.getGeoLocation();
                if (location != null) {
                    gpsLatitude = location.getLatitude();
                    gpsLongitude = location.getLongitude();
                }
            }

            Map<String, String> attributes = collectAttributes(metadata);
            boolean empty = author == null && copyright == null && cameraMake == null && cameraModel == null
                    && lensModel == null && software == null && dateTaken == null && orientation == null
                    && gpsLatitude == null && attributes.isEmpty();
            if (empty) {
                return null;
            }
            return new AssetMetadata(author, copyright, cameraMake, cameraModel, lensModel, software,
                    dateTaken, orientation, gpsLatitude, gpsLongitude, attributes);
        } catch (Exception e) {
            log.warn("读取图片元数据失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> collectAttributes(Metadata metadata) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Directory directory : metadata.getDirectories()) {
            for (Tag tag : directory.getTags()) {
                if (attributes.size() >= MAX_ATTRIBUTES) {
                    return attributes;
                }
                String description = tag.getDescription();
                if (description == null || description.isBlank()) {
                    continue;
                }
                attributes.put(directory.getName() + "." + tag.getTagName(), description);
            }
        }
        return attributes;
    }

    private String description(Directory directory, int tagType) {
        if (directory == null || !directory.containsTag(tagType)) {
            return null;
        }
        try {
            String value = directory.getDescription(tagType);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private Integer integer(Directory directory, int tagType) {
        if (directory == null || !directory.containsTag(tagType)) {
            return null;
        }
        try {
            return directory.getInt(tagType);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isRotated(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }
}
