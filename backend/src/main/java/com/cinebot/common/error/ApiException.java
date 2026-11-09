package com.cinebot.common.error;

import java.util.Map;

/**
 * The single exception type services throw for expected business failures.
 * Mapped to an RFC 7807 problem response by {@link GlobalExceptionHandler}; {@code properties} become extra
 * machine-readable fields (e.g. which seats were taken), so clients never parse the detail text.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties;

    public ApiException(ErrorCode code) {
        this(code, code.title());
    }

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
