package com.lumi.vault.business.apikey.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.apikey.dto.ApiKeyCreateRequest;
import com.lumi.vault.business.apikey.dto.ApiKeyCreateResponse;
import com.lumi.vault.business.apikey.dto.ApiKeyResponse;
import com.lumi.vault.business.apikey.entity.ApiKey;
import com.lumi.vault.business.apikey.mapper.ApiKeyMapper;
import com.lumi.vault.business.auth.entity.User;
import com.lumi.vault.business.auth.mapper.UserMapper;
import com.lumi.vault.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 对外 API 密钥：生成、列举、吊销、校验。
 *
 * <p>明文形如 {@code lv_<43 位 base64url>}，只在创建响应里返回一次；库里存 sha256 摘要与前缀，
 * 因此密钥泄露面仅限签发那一刻。校验走摘要等值查询（唯一索引），不存明文也不可反推。</p>
 */
@Slf4j
@Service
public class ApiKeyService {

    public static final String SCOPE_READ = "read";
    public static final String SCOPE_WRITE = "write";
    public static final String SECRET_PREFIX = "lv_";

    private static final List<String> ALL_SCOPES = List.of(SCOPE_READ, SCOPE_WRITE);
    private static final int SECRET_BYTES = 32;
    private static final int PREFIX_LENGTH = 12;
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ApiKeyMapper apiKeyMapper;
    private final UserMapper userMapper;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<Long, Instant> lastTouched = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyMapper apiKeyMapper, UserMapper userMapper) {
        this.apiKeyMapper = apiKeyMapper;
        this.userMapper = userMapper;
    }

    public ApiKeyCreateResponse create(Long userId, ApiKeyCreateRequest request) {
        List<String> scopes = normalizeScopes(request.scopes());
        String secret = SECRET_PREFIX + randomToken();
        ApiKey entity = new ApiKey();
        entity.setUserId(userId);
        entity.setName(request.name().trim());
        entity.setKeyPrefix(secret.substring(0, PREFIX_LENGTH));
        entity.setKeyHash(sha256(secret));
        entity.setScopes(String.join(",", scopes));
        if (request.expiresInDays() != null) {
            entity.setExpiresAt(Instant.now().plus(request.expiresInDays(), ChronoUnit.DAYS));
        }
        apiKeyMapper.insert(entity);
        log.info("签发 API 密钥 id={} user={} scopes={}", entity.getId(), userId, entity.getScopes());
        return new ApiKeyCreateResponse(ApiKeyResponse.from(entity, true), secret);
    }

    public Optional<ApiKey> find(Long id) {
        return Optional.ofNullable(apiKeyMapper.selectById(id));
    }

    public List<ApiKeyResponse> list(Long userId) {
        return apiKeyMapper.selectList(Wrappers.<ApiKey>lambdaQuery()
                        .eq(ApiKey::getUserId, userId)
                        .orderByDesc(ApiKey::getId))
                .stream()
                .map(key -> ApiKeyResponse.from(key, isActive(key)))
                .toList();
    }

    public void revoke(Long userId, Long keyId) {
        ApiKey key = apiKeyMapper.selectById(keyId);
        if (key == null || !Objects.equals(key.getUserId(), userId)) {
            throw ApiException.notFound("密钥不存在");
        }
        if (key.getRevokedAt() != null) {
            return;
        }
        key.setRevokedAt(Instant.now());
        apiKeyMapper.updateById(key);
        lastTouched.remove(keyId);
        log.info("吊销 API 密钥 id={} user={}", keyId, userId);
    }

    /**
     * 用明文密钥换取身份。返回空表示密钥无效（不存在 / 已吊销 / 已过期）。
     */
    public Optional<ApiKeyAuthenticated> authenticate(String rawSecret) {
        if (rawSecret == null || !rawSecret.startsWith(SECRET_PREFIX)) {
            return Optional.empty();
        }
        String secret = rawSecret.trim();
        if (secret.length() <= SECRET_PREFIX.length()) {
            return Optional.empty();
        }
        ApiKey key = apiKeyMapper.selectOne(Wrappers.<ApiKey>lambdaQuery()
                .eq(ApiKey::getKeyHash, sha256(secret))
                .last("limit 1"));
        if (key == null || !isActive(key)) {
            return Optional.empty();
        }
        User user = userMapper.selectById(key.getUserId());
        if (user == null) {
            return Optional.empty();
        }
        touch(key);
        return Optional.of(new ApiKeyAuthenticated(key, user, ApiKeyResponse.splitScopes(key.getScopes())));
    }

    private boolean isActive(ApiKey key) {
        if (key.getRevokedAt() != null) {
            return false;
        }
        return key.getExpiresAt() == null || key.getExpiresAt().isAfter(Instant.now());
    }

    /** 每次都写 last_used_at 会放大写库压力，按 id 节流到分钟级。 */
    private void touch(ApiKey key) {
        Instant now = Instant.now();
        Instant previous = lastTouched.get(key.getId());
        if (previous != null && Duration.between(previous, now).compareTo(TOUCH_INTERVAL) < 0) {
            return;
        }
        lastTouched.put(key.getId(), now);
        try {
            ApiKey patch = new ApiKey();
            patch.setId(key.getId());
            patch.setLastUsedAt(now);
            apiKeyMapper.updateById(patch);
        } catch (RuntimeException e) {
            log.debug("更新密钥使用时间失败: {}", e.getMessage());
        }
    }

    private List<String> normalizeScopes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return ALL_SCOPES;
        }
        List<String> scopes = requested.stream()
                .filter(Objects::nonNull)
                .map(scope -> scope.trim().toLowerCase(Locale.ROOT))
                .filter(scope -> !scope.isEmpty())
                .distinct()
                .toList();
        for (String scope : scopes) {
            if (!ALL_SCOPES.contains(scope)) {
                throw ApiException.badRequest("不支持的权限：" + scope + "（仅支持 read / write）");
            }
        }
        return scopes.isEmpty() ? ALL_SCOPES : scopes;
    }

    private String randomToken() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 通过密钥认证后的身份：用户 + 该密钥的权限。 */
    public record ApiKeyAuthenticated(ApiKey key, User user, List<String> scopes) {
    }
}
