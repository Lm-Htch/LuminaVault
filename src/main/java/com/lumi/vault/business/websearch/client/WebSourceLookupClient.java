package com.lumi.vault.business.websearch.client;

import com.lumi.vault.business.websearch.dto.WebSourceResult;

public interface WebSourceLookupClient {

    boolean available();

    /**
     * 反查图片来源；无命中返回 null；限流/鉴权异常抛 {@link WebSourceException}。
     */
    WebSourceResult lookup(byte[] image, String contentType);
}
