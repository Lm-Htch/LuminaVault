package com.lumi.vault.business.agent.controller;

import com.lumi.vault.business.agent.config.AiProperties;
import com.lumi.vault.business.agent.dto.AgentFeedbackRequest;
import com.lumi.vault.business.agent.dto.AgentSearchRequest;
import com.lumi.vault.business.agent.dto.AgentSearchResponse;
import com.lumi.vault.business.agent.dto.AssetAnalysisResponse;
import com.lumi.vault.business.agent.dto.SearchAttachmentResponse;
import com.lumi.vault.business.agent.dto.SearchFavoriteRequest;
import com.lumi.vault.business.agent.dto.SearchFavoriteResponse;
import com.lumi.vault.business.agent.dto.SearchHistoryResponse;
import com.lumi.vault.business.agent.service.AgentEvalService;
import com.lumi.vault.business.agent.service.AgentFeedbackService;
import com.lumi.vault.business.agent.service.AgentSearchAttachmentStore;
import com.lumi.vault.business.agent.service.AgentSearchHistoryService;
import com.lumi.vault.business.agent.service.AgentSearchService;
import com.lumi.vault.business.agent.service.AssetEnrichmentService;
import com.lumi.vault.business.auth.security.AuthenticatedUser;
import com.lumi.vault.business.graph.dto.GraphSettingsRequest;
import com.lumi.vault.business.graph.dto.GraphSettingsResponse;
import com.lumi.vault.business.graph.service.AssetSimilarityGraphService;
import com.lumi.vault.business.graph.service.GraphSettingsService;
import com.lumi.vault.common.Result;
import com.lumi.vault.exception.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentSearchService searchService;
    private final AgentFeedbackService feedbackService;
    private final AssetEnrichmentService enrichmentService;
    private final AgentEvalService evalService;
    private final AgentSearchHistoryService historyService;
    private final AgentSearchAttachmentStore attachmentStore;
    private final AssetSimilarityGraphService graphService;
    private final GraphSettingsService graphSettingsService;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public AgentController(AgentSearchService searchService,
                           AgentFeedbackService feedbackService,
                           AssetEnrichmentService enrichmentService,
                           AgentEvalService evalService,
                           AgentSearchHistoryService historyService,
                           AgentSearchAttachmentStore attachmentStore,
                           AssetSimilarityGraphService graphService,
                           GraphSettingsService graphSettingsService,
                           AiProperties aiProperties,
                           ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.feedbackService = feedbackService;
        this.enrichmentService = enrichmentService;
        this.evalService = evalService;
        this.historyService = historyService;
        this.attachmentStore = attachmentStore;
        this.graphService = graphService;
        this.graphSettingsService = graphSettingsService;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/search/attachment")
    public Result<SearchAttachmentResponse> uploadAttachment(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("图片不能为空");
        }
        String contentType = file.getContentType() == null ? "image/png" : file.getContentType();
        if (!contentType.startsWith("image/")) {
            throw ApiException.badRequest("仅支持图片文件");
        }
        if (file.getSize() > aiProperties.maxImageBytes()) {
            throw ApiException.badRequest("图片超过大小限制");
        }
        String id = attachmentStore.store(file.getBytes(), contentType);
        return Result.ok(new SearchAttachmentResponse(id));
    }

    @PostMapping("/search")
    public Result<AgentSearchResponse> search(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @Valid @RequestBody AgentSearchRequest request) {
        return Result.ok(searchService.search(principal.id(), request));
    }

    @PostMapping(value = "/search/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void searchStream(@AuthenticationPrincipal AuthenticatedUser principal,
                             @Valid @RequestBody AgentSearchRequest request,
                             HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer = response.getWriter();
        try {
            AgentSearchResponse result = searchService.search(principal.id(), request, false, event -> {
                try {
                    writeSseEvent(writer, "progress", objectMapper.writeValueAsString(event));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            writeSseEvent(writer, "result", objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.warn("流式搜索失败: {}", e.getMessage());
            try {
                writeSseEvent(writer, "error", objectMapper.writeValueAsString(
                        e.getMessage() == null ? "搜索失败" : e.getMessage()));
            } catch (Exception ignored) {
                // 客户端已断开
            }
        }
    }

    private void writeSseEvent(PrintWriter writer, String name, String data) throws IOException {
        writer.write("event:" + name + "\n");
        writer.write("data:" + data + "\n\n");
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("客户端已断开连接");
        }
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

    @PostMapping("/clip/pending")
    public Result<Integer> clipPending() {
        return Result.ok(enrichmentService.clipPending());
    }

    @PostMapping("/perception/pending")
    public Result<Integer> perceptionPending() {
        return Result.ok(enrichmentService.perceptionPending());
    }

    @GetMapping("/eval")
    public Result<AgentEvalService.AgentEvalResponse> eval(@RequestParam(defaultValue = "100") int limit) {
        return Result.ok(evalService.evaluate(limit));
    }

    @PostMapping("/graph/rebuild")
    public Result<Integer> rebuildGraph() {
        return Result.ok(graphService.rebuildAll());
    }

    @GetMapping("/graph/stats")
    public Result<Map<String, Object>> graphStats() {
        return Result.ok(Map.of("edges", graphService.edgeCount()));
    }

    @GetMapping("/graph/settings")
    public Result<GraphSettingsResponse> graphSettings() {
        return Result.ok(new GraphSettingsResponse(graphSettingsService.effective(), graphSettingsService.defaults()));
    }

    @PutMapping("/graph/settings")
    public Result<GraphSettingsResponse> updateGraphSettings(@Valid @RequestBody GraphSettingsRequest request) {
        graphSettingsService.update(request);
        return Result.ok(new GraphSettingsResponse(graphSettingsService.effective(), graphSettingsService.defaults()));
    }

    @DeleteMapping("/graph/settings")
    public Result<GraphSettingsResponse> resetGraphSettings() {
        graphSettingsService.reset();
        return Result.ok(new GraphSettingsResponse(graphSettingsService.effective(), graphSettingsService.defaults()));
    }

    @GetMapping("/history")
    public Result<List<SearchHistoryResponse>> history(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Result.ok(historyService.history(principal.id()));
    }

    @DeleteMapping("/history")
    public Result<Void> clearHistory(@AuthenticationPrincipal AuthenticatedUser principal) {
        historyService.clearHistory(principal.id());
        return Result.ok();
    }

    @DeleteMapping("/history/{id}")
    public Result<Void> deleteHistory(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @PathVariable Long id) {
        historyService.deleteHistory(principal.id(), id);
        return Result.ok();
    }

    @GetMapping("/favorites")
    public Result<List<SearchFavoriteResponse>> favorites(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Result.ok(historyService.favorites(principal.id()));
    }

    @PostMapping("/favorites")
    public Result<SearchFavoriteResponse> saveFavorite(@AuthenticationPrincipal AuthenticatedUser principal,
                                                       @Valid @RequestBody SearchFavoriteRequest request) {
        return Result.ok(historyService.saveFavorite(principal.id(), request));
    }

    @DeleteMapping("/favorites/{id}")
    public Result<Void> deleteFavorite(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @PathVariable Long id) {
        historyService.deleteFavorite(principal.id(), id);
        return Result.ok();
    }
}
