package com.lumi.vault.business.agent.controller;

import com.lumi.vault.business.agent.dto.AgentFeedbackRequest;
import com.lumi.vault.business.agent.dto.AgentSearchRequest;
import com.lumi.vault.business.agent.dto.AgentSearchResponse;
import com.lumi.vault.business.agent.dto.AssetAnalysisResponse;
import com.lumi.vault.business.agent.service.AgentFeedbackService;
import com.lumi.vault.business.agent.service.AgentSearchService;
import com.lumi.vault.business.agent.service.AssetEnrichmentService;
import com.lumi.vault.business.auth.security.AuthenticatedUser;
import com.lumi.vault.common.Result;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentSearchService searchService;
    private final AgentFeedbackService feedbackService;
    private final AssetEnrichmentService enrichmentService;

    public AgentController(AgentSearchService searchService,
                           AgentFeedbackService feedbackService,
                           AssetEnrichmentService enrichmentService) {
        this.searchService = searchService;
        this.feedbackService = feedbackService;
        this.enrichmentService = enrichmentService;
    }

    @PostMapping("/search")
    public Result<AgentSearchResponse> search(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @Valid @RequestBody AgentSearchRequest request) {
        return Result.ok(searchService.search(principal.id(), request));
    }

    @PostMapping("/feedback")
    public Result<Void> feedback(@AuthenticationPrincipal AuthenticatedUser principal,
                                 @Valid @RequestBody AgentFeedbackRequest request) {
        feedbackService.record(principal.id(), request);
        return Result.ok();
    }

    @GetMapping("/analysis/{assetId}")
    public Result<AssetAnalysisResponse> analysis(@PathVariable Long assetId) {
        return Result.ok(enrichmentService.analysis(assetId));
    }

    @PostMapping("/enrich/{assetId}")
    public Result<Void> enrich(@PathVariable Long assetId) {
        enrichmentService.enrichAsync(assetId);
        return Result.ok();
    }

    @PostMapping("/enrich/pending")
    public Result<Integer> enrichPending(@RequestParam(defaultValue = "false") boolean force) {
        return Result.ok(enrichmentService.enrichPending(force));
    }
}
