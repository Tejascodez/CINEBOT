package com.cinebot.common.web;

/** Request correlation id: header name and logging MDC key. */
public final class TraceId {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Request-Id";

    private TraceId() {
    }
}
