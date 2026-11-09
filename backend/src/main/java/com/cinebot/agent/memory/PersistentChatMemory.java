package com.cinebot.agent.memory;

import com.cinebot.agent.config.AgentProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring AI {@link ChatMemory} over the chatbot_messages table: the agent's persistent conversation memory.
 * <p>
 * Why not {@code MessageWindowChatMemory} + a repository: that pairing re-saves the WHOLE window on every add
 * (delete + insert all), and its window can start with a tool response whose tool call was cut off, which the
 * provider rejects. Here messages are appended once (an audit log of everything the agent did), and
 * {@link #get} returns the last N messages trimmed to start at a USER message, so tool call / response pairs
 * are never split.
 * <p>
 * System messages are never stored: instructions and state are rebuilt fresh for every request.
 */
@Component
public class PersistentChatMemory implements ChatMemory {

    /** Assistant-message metadata key carrying UI cards to store with the final answer (never sent to the model). */
    public static final String CARDS = "cinebot.cards";
    /** Provider metadata that must be replayed with tool calls (Gemini). */
    private static final String THOUGHT_SIGNATURES = "thoughtSignatures";

    record StoredToolCall(String id, String name, String arguments) {
    }

    record StoredToolResponse(String id, String name, String response) {
    }

    private final AgentMessageRepository messages;
    private final AgentProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public PersistentChatMemory(AgentMessageRepository messages, AgentProperties properties, ObjectMapper json,
                                Clock clock) {
        this.messages = messages;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void add(String conversationId, List<Message> newMessages) {
        UUID id = UUID.fromString(conversationId);
        for (Message message : newMessages) {
            AgentMessage row = switch (message) {
                case UserMessage user -> new AgentMessage(id, AgentMessage.Role.USER, user.getText(), null, null,
                        null, null, clock.instant());
                case AssistantMessage assistant -> new AgentMessage(id, AgentMessage.Role.ASSISTANT,
                        blankToNull(assistant.getText()),
                        assistant.hasToolCalls() ? write(assistant.getToolCalls().stream()
                                .map(c -> new StoredToolCall(c.id(), c.name(), c.arguments())).toList()) : null,
                        null,
                        assistant.getMetadata().get(CARDS) == null ? null : write(assistant.getMetadata().get(CARDS)),
                        providerMetadata(assistant), clock.instant());
                case ToolResponseMessage tool -> new AgentMessage(id, AgentMessage.Role.TOOL, null, null,
                        write(tool.getResponses().stream()
                                .map(r -> new StoredToolResponse(r.id(), r.name(), r.responseData())).toList()),
                        null, null, clock.instant());
                default -> null; // system messages are rebuilt per request, never stored
            };
            if (row != null) {
                messages.save(row);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> get(String conversationId) {
        List<AgentMessage> recent = new ArrayList<>(messages.findByConversationIdOrderByIdDesc(
                UUID.fromString(conversationId), Limit.of(properties.historySize())));
        Collections.reverse(recent);
        while (!recent.isEmpty() && recent.getFirst().getRole() != AgentMessage.Role.USER) {
            recent.removeFirst(); // never start the window in the middle of a tool exchange
        }
        return recent.stream().map(this::toMessage).toList();
    }

    @Override
    @Transactional
    public void clear(String conversationId) {
        messages.deleteByConversationId(UUID.fromString(conversationId));
    }

    /** What the user sees when reopening a conversation: their messages and the agent's answers, with cards. */
    @Transactional(readOnly = true)
    public List<VisibleMessage> visible(UUID conversationId) {
        return messages.findByConversationIdOrderById(conversationId).stream()
                .filter(m -> m.getContent() != null && m.getRole() != AgentMessage.Role.TOOL
                        && (m.getRole() == AgentMessage.Role.USER || m.getToolCalls() == null))
                .map(m -> new VisibleMessage(m.getRole().name(), m.getContent(), read(m.getCards()), m.getCreatedAt()))
                .toList();
    }

    public record VisibleMessage(String role, String content, JsonNode cards, java.time.Instant createdAt) {
    }

    private Message toMessage(AgentMessage m) {
        return switch (m.getRole()) {
            case USER -> new UserMessage(m.getContent());
            case ASSISTANT -> {
                List<AssistantMessage.ToolCall> calls = m.getToolCalls() == null ? List.of()
                        : readList(m.getToolCalls(), new TypeReference<List<StoredToolCall>>() { }).stream()
                        .map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.arguments())).toList();
                yield AssistantMessage.builder().content(m.getContent() == null ? "" : m.getContent())
                        .toolCalls(calls).properties(restoreProviderMetadata(m.getProviderMetadata())).build();
            }
            case TOOL -> ToolResponseMessage.builder().responses(
                    readList(m.getToolResponses(), new TypeReference<List<StoredToolResponse>>() { }).stream()
                            .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.response())).toList())
                    .build();
        };
    }

    private String providerMetadata(AssistantMessage message) {
        Object signatures = message.getMetadata().get(THOUGHT_SIGNATURES);
        if (!(signatures instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> encoded = list.stream()
                .map(s -> s instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : null)
                .filter(java.util.Objects::nonNull).toList();
        return encoded.isEmpty() ? null : write(Map.of(THOUGHT_SIGNATURES, encoded));
    }

    private Map<String, Object> restoreProviderMetadata(String stored) {
        Map<String, Object> metadata = new HashMap<>();
        JsonNode node = read(stored);
        if (node != null && node.has(THOUGHT_SIGNATURES)) {
            List<byte[]> signatures = new ArrayList<>();
            node.get(THOUGHT_SIGNATURES).forEach(s -> signatures.add(Base64.getDecoder().decode(s.asText())));
            metadata.put(THOUGHT_SIGNATURES, signatures);
        }
        return metadata;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise chat message", e);
        }
    }

    private JsonNode read(String value) {
        try {
            return value == null ? null : json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt chat message JSON", e);
        }
    }

    private <T> List<T> readList(String value, TypeReference<List<T>> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt chat message JSON", e);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
