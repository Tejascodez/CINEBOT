package com.cinebot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorates each Spring AI tool callback so that a tool call can never break the conversation:
 * <ol>
 *   <li>any failure (bad JSON arguments, validation, business error, outage) becomes a {@link ToolReply} error the
 *       model reads and explains, instead of an exception that aborts the whole turn;</li>
 *   <li>every call is recorded in the {@link AgentTurn} trace (the API's {@code toolCalls}).</li>
 * </ol>
 */
public final class GuardedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ObjectMapper json;

    public GuardedToolCallback(ToolCallback delegate, ObjectMapper json) {
        this.delegate = delegate;
        this.json = json;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = getToolDefinition().name();
        AgentTurn turn = AgentTurn.from(toolContext);
        String result;
        String status;
        String errorCode = null;
        try {
            requireJsonObject(toolInput);
            result = delegate.call(toolInput, toolContext);
            JsonNode parsed = json.readTree(result);
            status = parsed.path("status").asText("ok");
            errorCode = parsed.hasNonNull("errorCode") ? parsed.get("errorCode").asText() : null;
        } catch (Exception failure) {
            ToolReply reply = ToolErrors.toReply(name, failure);
            result = write(reply);
            status = reply.status();
            errorCode = reply.errorCode();
        }
        turn.record(new AgentTurn.ToolTrace(name, toolInput, status, errorCode));
        return result;
    }

    /**
     * Spring AI is lenient with malformed arguments (it may log the parse error and call the tool with partial
     * values). For tools that hold seats or cancel bookings, "partial" is not acceptable: reject up front.
     */
    private void requireJsonObject(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return; // no arguments: Spring AI passes {} (valid for tools whose parameters are all optional)
        }
        try {
            if (!json.readTree(toolInput).isObject()) {
                throw new InvalidToolArgumentException("arguments must be a JSON object");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new InvalidToolArgumentException("arguments are not valid JSON");
        }
    }

    private String write(ToolReply reply) {
        try {
            return json.writeValueAsString(reply);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"status\":\"error\",\"errorCode\":\"TOOL_FAILED\"}";
        }
    }
}
