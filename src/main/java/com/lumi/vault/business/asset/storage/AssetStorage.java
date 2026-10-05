package com.lumi.vault.business.asset.storage;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;

public interface AssetStorage {

    void put(String key, Path file, String contentType);

    byte[] get(String key);

    URL presignGet(String key, String filename, boolean download, Duration ttl);

    void delete(String key);
}
