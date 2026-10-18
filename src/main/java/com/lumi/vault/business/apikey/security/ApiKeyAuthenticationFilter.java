package com.lumi.vault.business.apikey.security;

import com.lumi.vault.business.apikey.service.ApiKeyService;
import com.lumi.vault.business.auth.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 识别对外 API 密钥：{@code X-API-Key: lv_xxx} 或 {@code Authorization: Bearer lv_xxx}。
 *
 * <p>必须排在 {@link com.lumi.vault.business.auth.security.JwtAuthenticationFilter} 之前 ——
 * 否则 JWT 过滤器会先把 lv_ 前缀的密钥当 JWT 解析失败并清空上下文。</p>
 *
 * <p>签发的权限形如 {@code SCOPE_read} / {@code SCOPE_write}，另附 {@code ROLE_API_KEY}
 * 以便与登录用户（{@code ROLE_USER}）区分 —— 例如密钥不能调用密钥管理接口。</p>
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String secret = resolveSecret(request);
            if (secret != null) {
                apiKeyService.authenticate(secret).ifPresent(authenticated -> {
                    AuthenticatedUser principal = new AuthenticatedUser(
                            authenticated.user().getId(),
                            authenticated.user().getUsername(),
                            "apikey:" + authenticated.key().getId(),
                            authenticated.key().getExpiresAt());
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_API_KEY"));
                    for (String scope : authenticated.scopes()) {
                        authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope.toLowerCase(Locale.ROOT)));
                    }
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveSecret(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (token.startsWith(ApiKeyService.SECRET_PREFIX)) {
                return token;
            }
        }
        return null;
    }
}
