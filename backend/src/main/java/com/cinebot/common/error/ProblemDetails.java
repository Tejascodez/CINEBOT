package com.cinebot.common.error;

import com.cinebot.common.web.TraceId;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/** Builds the one error body shape used everywhere: MVC handlers, security filters, and (later) webhooks. */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        withTraceId(problem);
        return problem;
    }

    public static ProblemDetail withTraceId(ProblemDetail problem) {
        String traceId = MDC.get(TraceId.MDC_KEY);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

}
