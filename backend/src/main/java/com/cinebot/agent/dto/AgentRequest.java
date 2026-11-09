package com.cinebot.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * @param conversationId        omit to start a new conversation; the response returns the id to use from then on
 * @param declineConfirmationId set by the confirmation card's "No" button: the backend withdraws that pending hold
 *                              or cancellation before the model runs, so it cannot be confirmed by mistake
 */
public record AgentRequest(UUID conversationId, @NotBlank @Size(max = 1000) String message,
                           @Size(max = 20) String declineConfirmationId) {
}
