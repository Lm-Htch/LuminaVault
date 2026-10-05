package com.lumi.vault.common;

public record Result<T>(int code, String message, T data) {

    private static final int SUCCESS_CODE = 200;
    private static final String SUCCESS_MESSAGE = "ok";

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static Result<Void> ok() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> error(int code, String message, T data) {
        return new Result<>(code, message, data);
    }
}
