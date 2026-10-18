package com.lumi.vault.websearch;

import com.lumi.vault.business.websearch.client.WebSourceLookupClient;
import com.lumi.vault.business.websearch.client.WebSourceException;
import com.lumi.vault.business.websearch.dto.WebSourceResult;

public class FakeWebSourceLookupClient implements WebSourceLookupClient {

    private WebSourceResult result;
    private WebSourceException exception;
    private int calls;

    public void setResult(WebSourceResult result) {
        this.result = result;
    }

    public void setException(WebSourceException exception) {
        this.exception = exception;
    }

    public int calls() {
        return calls;
    }

    public void reset() {
        result = null;
        exception = null;
        calls = 0;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public WebSourceResult lookup(byte[] image, String contentType) {
        calls++;
        if (exception != null) {
            throw exception;
        }
        return result;
    }
}
