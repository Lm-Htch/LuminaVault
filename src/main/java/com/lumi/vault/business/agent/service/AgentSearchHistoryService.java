package com.lumi.vault.business.agent.service;

import lombok.extern.slf4j.Slf4j;


import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.dto.SearchFavoriteRequest;
import com.lumi.vault.business.agent.dto.SearchFavoriteResponse;
import com.lumi.vault.business.agent.dto.SearchHistoryResponse;
import com.lumi.vault.business.agent.entity.SearchFavorite;
import com.lumi.vault.business.agent.entity.SearchHistory;
import com.lumi.vault.business.agent.mapper.SearchFavoriteMapper;
import com.lumi.vault.business.agent.mapper.SearchHistoryMapper;
import com.lumi.vault.exception.ApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 搜索历史（只存查询词）与「保存的搜索」（查询词 + 资产 ID 快照）。历史不缓存结果。
 */
@Service
@Slf4j
public class AgentSearchHistoryService {
    private static final int HISTORY_LIMIT = 20;
    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_FAVORITE_ASSETS = 100;

    private final SearchHistoryMapper historyMapper;
    private final SearchFavoriteMapper favoriteMapper;

    public AgentSearchHistoryService(SearchHistoryMapper historyMapper, SearchFavoriteMapper favoriteMapper) {
        this.historyMapper = historyMapper;
        this.favoriteMapper = favoriteMapper;
    }

    public void record(long userId, String query) {
        try {
            String trimmed = query == null ? "" : query.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            String stored = trimmed.length() > MAX_QUERY_LENGTH ? trimmed.substring(0, MAX_QUERY_LENGTH) : trimmed;
            SearchHistory existing = historyMapper.selectOne(Wrappers.<SearchHistory>lambdaQuery()
                    .eq(SearchHistory::getUserId, userId)
                    .eq(SearchHistory::getQueryText, stored)
                    .last("limit 1"));
            if (existing == null) {
                SearchHistory row = new SearchHistory();
                row.setUserId(userId);
                row.setQueryText(stored);
                historyMapper.insert(row);
                return;
            }
            historyMapper.update(null, Wrappers.<SearchHistory>lambdaUpdate()
                    .eq(SearchHistory::getId, existing.getId())
                    .set(SearchHistory::getCreatedAt, Instant.now()));
        } catch (Exception e) {
            log.debug("记录搜索历史失败: {}", e.getMessage());
        }
    }

    public List<SearchHistoryResponse> history(long userId) {
        return historyMapper.selectList(Wrappers.<SearchHistory>lambdaQuery()
                        .eq(SearchHistory::getUserId, userId)
                        .orderByDesc(SearchHistory::getCreatedAt)
                        .orderByDesc(SearchHistory::getId)
                        .last("limit " + HISTORY_LIMIT))
                .stream()
                .map(SearchHistoryResponse::from)
                .toList();
    }

    public void deleteHistory(long userId, Long id) {
        historyMapper.delete(Wrappers.<SearchHistory>lambdaQuery()
                .eq(SearchHistory::getUserId, userId)
                .eq(SearchHistory::getId, id));
    }

    public void clearHistory(long userId) {
        historyMapper.delete(Wrappers.<SearchHistory>lambdaQuery().eq(SearchHistory::getUserId, userId));
    }

    public SearchFavoriteResponse saveFavorite(long userId, SearchFavoriteRequest request) {
        List<Long> ids = request.assetIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_FAVORITE_ASSETS)
                .toList();
        if (ids.isEmpty()) {
            throw ApiException.badRequest("assetIds 不能为空");
        }
        SearchFavorite favorite = new SearchFavorite();
        favorite.setUserId(userId);
        favorite.setQueryText(request.query().trim());
        favorite.setAssetIds(joinIds(ids));
        favoriteMapper.insert(favorite);
        return toResponse(favorite);
    }

    public List<SearchFavoriteResponse> favorites(long userId) {
        return favoriteMapper.selectList(Wrappers.<SearchFavorite>lambdaQuery()
                        .eq(SearchFavorite::getUserId, userId)
                        .orderByDesc(SearchFavorite::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public void deleteFavorite(long userId, Long id) {
        favoriteMapper.delete(Wrappers.<SearchFavorite>lambdaQuery()
                .eq(SearchFavorite::getUserId, userId)
                .eq(SearchFavorite::getId, id));
    }

    private String joinIds(List<Long> ids) {
        StringBuilder builder = new StringBuilder();
        for (Long id : ids) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    private SearchFavoriteResponse toResponse(SearchFavorite favorite) {
        List<Long> ids = new ArrayList<>();
        if (favorite.getAssetIds() != null && !favorite.getAssetIds().isBlank()) {
            for (String part : favorite.getAssetIds().split(",")) {
                try {
                    ids.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    // 跳过损坏的 id
                }
            }
        }
        return new SearchFavoriteResponse(favorite.getId(), favorite.getQueryText(), ids, favorite.getCreatedAt());
    }
}
