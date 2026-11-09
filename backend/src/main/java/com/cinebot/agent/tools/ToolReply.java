package com.cinebot.agent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The ONLY shape a tool ever returns to the model (serialised to JSON by Spring AI).
 * <ul>
 *   <li>{@code summary}: one factual sentence the model can rely on (and must not contradict),</li>
 *   <li>{@code data}: ids and facts the model needs for its next decision (e.g. showId to pass to the next tool),</li>
 *   <li>{@code errorCode}: machine-readable failure, so the model can pick a recovery (ask, retry differently...).</li>
 * </ul>
 * Errors are results, not exceptions: the model is told what went wrong and explains it instead of guessing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolReply(String status, String summary, Object data, String errorCode) {

    public static ToolReply ok(String summary, Object data) {
        return new ToolReply("ok", summary, data, null);
    }

    public static ToolReply error(String errorCode, String summary) {
        return new ToolReply("error", summary, null, errorCode);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isOk() {
        return "ok".equals(status);
    }
}
