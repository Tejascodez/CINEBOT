package com.cinebot.agent.dto;

import com.cinebot.agent.tools.AgentCard;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * @param response            the agent's answer, in natural language
 * @param toolCalls           every backend operation executed for this message, in order (audit / debugging / UI)
 * @param cards               UI payloads (movie list, seat map, confirmation summary, payment button...)
 * @param pendingConfirmation present while a hold or cancellation waits for the user's yes/no
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentResponse(UUID conversationId, String response, List<ToolCallView> toolCalls,
                            List<AgentCard> cards, PendingConfirmation pendingConfirmation) {

    public record ToolCallView(String name, JsonNode arguments, String status, String errorCode) {
    }

    public record PendingConfirmation(String confirmationId, String type, String description) {
    }
}
