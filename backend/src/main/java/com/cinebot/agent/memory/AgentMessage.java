package com.cinebot.agent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One stored message: USER text, ASSISTANT text and/or tool calls, or TOOL responses. JSON columns are mapped
 * as text and (de)serialised by {@link PersistentChatMemory}, so their exact shape is under our control.
 */
@Entity
@Table(name = "chatbot_messages")
class AgentMessage {

    enum Role { USER, ASSISTANT, TOOL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(nullable = false, length = 10)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Role role;

    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private String toolCalls;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_responses", columnDefinition = "jsonb")
    private String toolResponses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String cards;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_metadata", columnDefinition = "jsonb")
    private String providerMetadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentMessage() {
        // JPA
    }

    AgentMessage(UUID conversationId, Role role, String content, String toolCalls, String toolResponses,
                 String cards, String providerMetadata, Instant createdAt) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolResponses = toolResponses;
        this.cards = cards;
        this.providerMetadata = providerMetadata;
        this.createdAt = createdAt;
    }

    Long getId() { return id; }
    Role getRole() { return role; }
    String getContent() { return content; }
    String getToolCalls() { return toolCalls; }
    String getToolResponses() { return toolResponses; }
    String getCards() { return cards; }
    String getProviderMetadata() { return providerMetadata; }
    Instant getCreatedAt() { return createdAt; }
}
