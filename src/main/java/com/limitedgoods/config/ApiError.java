package com.limitedgoods.config;
public class ApiError extends RuntimeException {
    public final int status;
    public final String code;
    public ApiError(int status, String code) { super(code); this.status = status; this.code = code; }
}
