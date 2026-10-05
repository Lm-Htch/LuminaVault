package com.lumi.vault.business.asset.storage;

import com.lumi.vault.config.StorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

@Component
public class S3AssetStorage implements AssetStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties properties;

    public S3AssetStorage(S3Client s3Client, S3Presigner s3Presigner, StorageProperties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    @Override
    public void put(String key, Path file, String contentType) {
        s3Client.putObject(builder -> builder
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType), RequestBody.fromFile(file));
    }

    @Override
    public byte[] get(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build();
        return s3Client.getObjectAsBytes(request).asByteArray();
    }

    @Override
    public URL presignGet(String key, String filename, boolean download, Duration ttl) {
        String disposition = (download ? "attachment" : "inline")
                + "; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .responseContentDisposition(disposition)
                .build();
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(builder -> builder
                .signatureDuration(ttl)
                .getObjectRequest(request));
        return presigned.url();
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(builder -> builder
                .bucket(properties.bucket())
                .key(key));
    }
}
