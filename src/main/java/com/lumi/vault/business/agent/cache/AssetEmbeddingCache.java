package com.lumi.vault.business.agent.cache;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.entity.AssetEmbedding;
import com.lumi.vault.business.agent.mapper.AssetEmbeddingMapper;
import com.lumi.vault.business.agent.util.VectorUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AssetEmbeddingCache {

    private final AssetEmbeddingMapper embeddingMapper;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, Snapshot> cache = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private volatile boolean loaded = false;

    public AssetEmbeddingCache(AssetEmbeddingMapper embeddingMapper, ObjectMapper objectMapper) {
        this.embeddingMapper = embeddingMapper;
        this.objectMapper = objectMapper;
    }

    public record Snapshot(Long assetId, String description, String tags,
                           double[] text, String textModel, double[] image, String imageModel) {
    }

    public Map<Long, Snapshot> all() {
        if (!loaded) {
            synchronized (lock) {
                if (!loaded) {
                    reload();
                    loaded = true;
                }
            }
        }
        return cache;
    }

    public void refresh(Long assetId) {
        if (!loaded) {
            return;
        }
        AssetEmbedding row = embeddingMapper.selectById(assetId);
        if (row == null || !AssetEmbedding.STATUS_DONE.equals(row.getStatus())) {
            cache.remove(assetId);
            return;
        }
        cache.put(assetId, toSnapshot(row));
    }

    public void evict(Long assetId) {
        cache.remove(assetId);
    }

    private void reload() {
        cache.clear();
        for (AssetEmbedding row : embeddingMapper.selectList(Wrappers.<AssetEmbedding>lambdaQuery()
                .eq(AssetEmbedding::getStatus, AssetEmbedding.STATUS_DONE))) {
            cache.put(row.getAssetId(), toSnapshot(row));
        }
    }

    private Snapshot toSnapshot(AssetEmbedding row) {
        return new Snapshot(
                row.getAssetId(),
                row.getDescription(),
                row.getTags(),
                VectorUtils.fromJson(row.getEmbedding(), objectMapper),
                row.getModel(),
                VectorUtils.fromJson(row.getImageEmbedding(), objectMapper),
                row.getImageModel());
    }
}
