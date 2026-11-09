package com.cinebot.agent.memory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    List<AgentMessage> findByConversationIdOrderByIdDesc(UUID conversationId, Limit limit);

    List<AgentMessage> findByConversationIdOrderById(UUID conversationId);

    void deleteByConversationId(UUID conversationId);
}
