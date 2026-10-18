package com.lumi.vault.business.agent.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 智能搜索粘贴图片的临时存储（内存，15 分钟过期，最多 20 张，超出淘汰最旧）。
 */
@Component
public class AgentSearchAttachmentStore {

    private static final int MAX_ENTRIES = 20;
    private static final Duration TTL = Duration.ofMinutes(15);

    public record Attachment(byte[] data, String contentType, Instant expiresAt) {
    }

    private final Map<String, Attachment> store = new LinkedHashMap<>();

    public synchronized String store(byte[] data, String contentType) {
        purgeExpired();
        while (store.size() >= MAX_ENTRIES) {
            Iterator<String> iterator = store.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        String id = UUID.randomUUID().toString();
        store.put(id, new Attachment(data, contentType, Instant.now().plus(TTL)));
        return id;
    }

    public synchronized Attachment get(String id) {
        if (id == null) {
            return null;
        }
        Attachment attachment = store.get(id);
        if (attachment == null) {
            return null;
        }
        if (attachment.expiresAt().isBefore(Instant.now())) {
            store.remove(id);
            return null;
        }
        return attachment;
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        store.values().removeIf(attachment -> attachment.expiresAt().isBefore(now));
    }
}
