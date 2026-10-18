package com.lumi.vault.business.openapi.controller;

import com.lumi.vault.business.agent.dto.AgentSearchRequest;
import com.lumi.vault.business.agent.dto.AgentSearchResponse;
import com.lumi.vault.business.agent.dto.SearchAttachmentResponse;
import com.lumi.vault.business.agent.config.AiProperties;
import com.lumi.vault.business.agent.service.AgentSearchAttachmentStore;
import com.lumi.vault.business.agent.service.AgentSearchService;
import com.lumi.vault.business.auth.security.AuthenticatedUser;
import com.lumi.vault.common.Result;
import com.lumi.vault.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 开放 API：自然语言 / 以图搜图。
 */
@RestController
@RequestMapping("/api/open/v1/search")
public class OpenSearchController {

    private final AgentSearchService searchService;
    private final AgentSearchAttachmentStore attachmentStore;
    private final AiProperties aiProperties;

    public OpenSearchController(AgentSearchService searchService,
                                AgentSearchAttachmentStore attachmentStore,
                                AiProperties aiProperties) {
        this.searchService = searchService;
        this.attachmentStore = attachmentStore;
        this.aiProperties = aiProperties;
    }

    @PostMapping
    public Result<AgentSearchResponse> search(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @Valid @RequestBody AgentSearchRequest request) {
        return Result.ok(searchService.search(principal.id(), request));
    }

    @PostMapping("/attachment")
    public Result<SearchAttachmentResponse> uploadAttachment(@RequestParam("file") MultipartFile file)
            throws IOException {
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
        return Result.ok(new SearchAttachmentResponse(attachmentStore.store(file.getBytes(), contentType)));
    }
}
