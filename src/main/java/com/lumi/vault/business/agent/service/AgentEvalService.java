package com.lumi.vault.business.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumi.vault.business.agent.dto.AgentSearchRequest;
import com.lumi.vault.business.agent.dto.AgentSearchResponse;
import com.lumi.vault.business.agent.dto.AgentSearchResult;
import com.lumi.vault.business.agent.entity.AssetFeedback;
import com.lumi.vault.business.agent.mapper.AssetFeedbackMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线评测：用历史反馈（query → 选中资产）重放检索，计算 MRR@10 / Recall@50。
 */
@Service
public class AgentEvalService {

    private final AssetFeedbackMapper feedbackMapper;
    private final AgentSearchService searchService;

    public AgentEvalService(AssetFeedbackMapper feedbackMapper, AgentSearchService searchService) {
        this.feedbackMapper = feedbackMapper;
        this.searchService = searchService;
    }

    public record PairEvaluation(String query, long assetId, int rank) {
    }

    public record AgentEvalResponse(int pairs, int hitAt10, double hitRateAt10, double mrrAt10,
                                    double recallAt50, List<PairEvaluation> details) {
    }

    public AgentEvalResponse evaluate(int limit) {
        int max = Math.max(1, Math.min(limit, 500));
        List<AssetFeedback> rows = feedbackMapper.selectList(Wrappers.<AssetFeedback>lambdaQuery()
                .eq(AssetFeedback::getAction, AssetFeedback.ACTION_SELECT)
                .isNotNull(AssetFeedback::getQueryText)
                .orderByDesc(AssetFeedback::getId)
                .last("limit " + (max * 5)));
        Map<String, AssetFeedback> pairs = new LinkedHashMap<>();
        for (AssetFeedback row : rows) {
            String query = row.getQueryText() == null ? null : row.getQueryText().trim();
            if (query == null || query.isEmpty()) {
                continue;
            }
            pairs.putIfAbsent(row.getUserId() + "|" + query + "|" + row.getAssetId(), row);
            if (pairs.size() >= max) {
                break;
            }
        }
        int hitAt10 = 0;
        int recallAt50 = 0;
        double mrr = 0;
        List<PairEvaluation> details = new ArrayList<>();
        for (AssetFeedback row : pairs.values()) {
            AgentSearchResponse response = searchService.search(row.getUserId(),
                    new AgentSearchRequest(row.getQueryText(), 50, null, null), true);
            int rank = rankOf(response.results(), row.getAssetId());
            details.add(new PairEvaluation(row.getQueryText().trim(), row.getAssetId(), rank));
            if (rank > 0) {
                recallAt50++;
                if (rank <= 10) {
                    hitAt10++;
                    mrr += 1.0 / rank;
                }
            }
        }
        int total = pairs.size();
        return new AgentEvalResponse(
                total,
                hitAt10,
                total == 0 ? 0 : (double) hitAt10 / total,
                total == 0 ? 0 : mrr / total,
                total == 0 ? 0 : (double) recallAt50 / total,
                details);
    }

    private int rankOf(List<AgentSearchResult> results, long assetId) {
        for (int index = 0; index < results.size(); index++) {
            if (results.get(index).assetId() != null && results.get(index).assetId() == assetId) {
                return index + 1;
            }
        }
        return 0;
    }
}
