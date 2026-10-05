package com.lumi.vault.asset;

import com.lumi.vault.business.asset.storage.AssetStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryAssetStorage implements AssetStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, Path file, String contentType) {
        try {
            objects.put(key, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] get(String key) {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new IllegalStateException("对象不存在: " + key);
        }
        return bytes;
    }

    @Override
    public URL presignGet(String key, String filename, boolean download, Duration ttl) {
        try {
            return URI.create("http://localhost:8333/fake/" + key + "?download=" + download).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    public int objectCount() {
        return objects.size();
    }

    public void clear() {
        objects.clear();
    }
}
