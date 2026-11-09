package com.cinebot.agent.memory;

import com.cinebot.agent.tools.ConversationState;
import com.cinebot.auth.AuthenticatedUser;
import com.cinebot.common.error.ApiException;
import com.cinebot.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Conversations: creation, ownership, the per-conversation lease, and the persisted {@link ConversationState}.
 * Ownership mirrors bookings: someone else's conversation id is indistinguishable from a non-existent one (404).
 */
@Component
public class ConversationStore {

    /** Longer than any realistic turn (model calls + tools); a crashed turn frees the conversation after this. */
    static final Duration LEASE = Duration.ofMinutes(2);

    private final AgentConversationRepository conversations;
    private final ObjectMapper json;
    private final Clock clock;

    public ConversationStore(AgentConversationRepository conversations, ObjectMapper json, Clock clock) {
        this.conversations = conversations;
        this.json = json.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.clock = clock;
    }

    public record Conversation(UUID id, Instant createdAt, ConversationState state) {
    }

    public Conversation create(AuthenticatedUser user) {
        AgentConversation c = conversations.save(new AgentConversation(UUID.randomUUID(), user.id(), clock.instant()));
        return new Conversation(c.getId(), c.getCreatedAt(), new ConversationState());
    }

    public Conversation requireOwned(AuthenticatedUser user, UUID id) {
        AgentConversation c = conversations.findByIdAndUserId(id, user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND));
        return new Conversation(c.getId(), c.getCreatedAt(), readState(c.getContext()));
    }

    /** One message at a time per conversation: a double-submitted "yes" must not run twice in parallel. */
    public void lease(AuthenticatedUser user, UUID id) {
        Instant now = clock.instant();
        if (conversations.tryLease(id, user.id(), now, now.plus(LEASE)) == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_BUSY);
        }
    }

    public void release(UUID id) {
        conversations.releaseLease(id);
    }

    public void saveState(UUID id, ConversationState state) {
        try {
            conversations.saveContext(id, json.writeValueAsString(state), clock.instant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise conversation state", e);
        }
    }

    private ConversationState readState(String context) {
        try {
            // Step 7 sessions stored {showId, lockId, bookingId}: same field names, so they carry over.
            return context == null ? new ConversationState() : json.readValue(context, ConversationState.class);
        } catch (JsonProcessingException e) {
            return new ConversationState(); // unreadable state: start fresh rather than fail the conversation
        }
    }
}
