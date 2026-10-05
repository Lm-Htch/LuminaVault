package com.lumi.vault.business.model.client;

import com.lumi.vault.business.model.dto.ModelAnalysis;

public interface ImageModelClient {

    boolean available();

    ModelAnalysis analyze(byte[] image, String contentType);
}
