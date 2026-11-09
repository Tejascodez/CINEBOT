package com.cinebot.agent.dto;

import com.cinebot.agent.memory.PersistentChatMemory.VisibleMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(UUID conversationId, Instant createdAt, List<VisibleMessage> messages,
                                   AgentResponse.PendingConfirmation pendingConfirmation) {
}
