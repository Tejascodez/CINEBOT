package com.cinebot.agent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * A conversation, owned by exactly one user. {@code context} is the serialised ConversationState (JSON text,
 * mapped with the application's ObjectMapper by {@link ConversationStore}). busy_until is managed by
 * {@link ConversationStore} with conditional UPDATEs, so it is deliberately not mapped here.
 */
@Entity
@Table(name = "chatbot_sessions")
public class AgentConversation implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String context = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    @Transient
    private boolean isNew;

    protected AgentConversation() {
        // JPA
    }

    AgentConversation(UUID id, Long userId, Instant now) {
        this.id = id;
        this.userId = userId;
        this.createdAt = now;
        this.lastActiveAt = now;
        this.isNew = true;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public Long getUserId() {
        return userId;
    }

    String getContext() {
        return context;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
