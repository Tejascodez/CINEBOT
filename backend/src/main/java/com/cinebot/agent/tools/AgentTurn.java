package com.cinebot.agent.tools;

import com.cinebot.auth.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Everything a tool needs about the CURRENT user message, passed through Spring AI's {@link ToolContext}.
 * The tool context is filled by our code, never by the model: the model cannot choose which user it acts as.
 * <p>
 * One instance per user message; tools of the same turn run sequentially and share it.
 */
public final class AgentTurn {

    public static final String KEY = "cinebot.agentTurn";

    private final AuthenticatedUser user;
    private final ConversationState state;
    private final Instant now;
    private final List<AgentCard> cards = new ArrayList<>();
    private final List<ToolTrace> trace = new ArrayList<>();

    /** One executed tool call, reported in the API response ({@code toolCalls}). */
    public record ToolTrace(String name, String arguments, String status, String errorCode) {
    }

    public AgentTurn(AuthenticatedUser user, ConversationState state, Instant now) {
        this.user = user;
        this.state = state;
        this.now = now;
    }

    public static AgentTurn from(ToolContext context) {
        Object turn = context == null ? null : context.getContext().get(KEY);
        if (!(turn instanceof AgentTurn agentTurn)) {
            throw new IllegalStateException("Tool invoked outside an agent turn");
        }
        return agentTurn;
    }

    public Map<String, Object> asToolContext() {
        return Map.of(KEY, this);
    }

    public AuthenticatedUser user() {
        return user;
    }

    public ConversationState state() {
        return state;
    }

    public Instant now() {
        return now;
    }

    public void show(AgentCard card) {
        cards.add(card);
    }

    public List<AgentCard> cards() {
        return List.copyOf(cards);
    }

    void record(ToolTrace call) {
        trace.add(call);
    }

    public List<ToolTrace> trace() {
        return List.copyOf(trace);
    }
}
