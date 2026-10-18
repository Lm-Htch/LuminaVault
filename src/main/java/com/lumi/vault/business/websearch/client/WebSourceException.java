package com.lumi.vault.business.websearch.client;

public class WebSourceException extends RuntimeException {

    private final boolean quota;

    public WebSourceException(String message, boolean quota) {
        super(message);
        this.quota = quota;
    }

    public boolean quota() {
        return quota;
    }
}
