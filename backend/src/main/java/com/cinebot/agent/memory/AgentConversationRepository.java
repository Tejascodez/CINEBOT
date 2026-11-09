package com.cinebot.agent.memory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

interface AgentConversationRepository extends JpaRepository<AgentConversation, UUID> {

    Optional<AgentConversation> findByIdAndUserId(UUID id, Long userId);

    /** Claims the conversation for one message; 0 rows = another message is still being processed. */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE chatbot_sessions SET busy_until = :until
            WHERE id = :id AND user_id = :userId AND (busy_until IS NULL OR busy_until < :now)""", nativeQuery = true)
    int tryLease(UUID id, Long userId, Instant now, Instant until);

    @Transactional
    @Modifying
    @Query(value = "UPDATE chatbot_sessions SET busy_until = NULL WHERE id = :id", nativeQuery = true)
    void releaseLease(UUID id);

    @Transactional
    @Modifying
    @Query(value = """
            UPDATE chatbot_sessions SET context = CAST(:context AS jsonb), last_active_at = :now
            WHERE id = :id""", nativeQuery = true)
    void saveContext(UUID id, String context, Instant now);
}
