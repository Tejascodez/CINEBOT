package com.cinebot.agent.tools;

import com.cinebot.common.error.ApiException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;

/**
 * Translates a failed tool call into a {@link ToolReply} the model can act on.
 * <p>
 * Business errors ({@link ApiException}) are passed through: their codes and messages are written for API clients
 * and safe to show ("Too close to showtime to cancel"). Everything else is reduced to a generic message, so SQL,
 * stack traces, hostnames or internal class names never reach the model (and from there, the user).
 */
final class ToolErrors {

    private static final Logger log = LoggerFactory.getLogger(ToolErrors.class);

    private ToolErrors() {
    }

    static ToolReply toReply(String toolName, Throwable failure) {
        Throwable root = root(failure);
        if (!thrownByOurCode(root)) {
            // Failed inside Spring AI before our method ran: converting the model's JSON into the parameters
            // (malformed JSON, "showId": "abc"). That is the model's mistake, not a backend failure.
            return ToolReply.error("INVALID_ARGUMENTS",
                    "The arguments do not match this tool's parameter types. Fix them or ask the user.");
        }
        Throwable cause = unwrap(failure);
        if (cause instanceof ApiException api) {
            return ToolReply.error(api.code().name(), api.getMessage());
        }
        if (cause instanceof ConfirmationGate.Rejected rejected) {
            return ToolReply.error(rejected.code(), rejected.getMessage());
        }
        if (cause instanceof InvalidToolArgumentException invalid) {
            return ToolReply.error("INVALID_ARGUMENTS", invalid.getMessage() + ". Ask the user or fix the arguments.");
        }
        if (cause instanceof ConstraintViolationException violations) {
            return ToolReply.error("INVALID_ARGUMENTS", violations.getConstraintViolations().stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage()).collect(Collectors.joining("; ")));
        }
        if (cause instanceof QueryTimeoutException || cause instanceof TransientDataAccessException) {
            log.warn("Agent tool {} timed out / transient failure", toolName, cause);
            return ToolReply.error("BACKEND_TIMEOUT",
                    "The booking system is slow right now. Tell the user and suggest trying again shortly.");
        }
        if (cause instanceof DataAccessException) {
            log.error("Agent tool {} failed in the data layer", toolName, cause);
        } else {
            log.error("Agent tool {} failed", toolName, cause);
        }
        return ToolReply.error("TOOL_FAILED",
                "The booking system could not complete this request. Do not guess the result; tell the user.");
    }

    private static Throwable root(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** Was any frame between the throw site and Spring AI's method invocation CineBot code? */
    private static boolean thrownByOurCode(Throwable root) {
        for (StackTraceElement frame : root.getStackTrace()) {
            if (frame.getClassName().startsWith("org.springframework.ai.tool.method.")) {
                return false; // reached the Spring AI invoker without passing through our code
            }
            if (frame.getClassName().startsWith("com.cinebot.")) {
                return true;
            }
        }
        return true;
    }

    private static Throwable unwrap(Throwable t) {
        Throwable current = t;
        // ToolExecutionException / IllegalStateException(JsonProcessingException) / InvocationTargetException
        while (current.getCause() != null && !(current instanceof ApiException)
                && !(current instanceof InvalidToolArgumentException)
                && !(current instanceof ConfirmationGate.Rejected)
                && !(current instanceof ConstraintViolationException)
                && !(current instanceof DataAccessException)) {
            current = current.getCause();
        }
        return current;
    }
}
