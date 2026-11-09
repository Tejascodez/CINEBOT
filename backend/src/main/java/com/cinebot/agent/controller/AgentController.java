package com.cinebot.agent.controller;

import com.cinebot.agent.dto.AgentRequest;
import com.cinebot.agent.dto.AgentResponse;
import com.cinebot.agent.dto.ConversationResponse;
import com.cinebot.agent.service.MovieBookingAgentService;
import com.cinebot.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The conversational booking API. Authenticated: the agent always acts as the signed-in user. */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final MovieBookingAgentService agent;

    public AgentController(MovieBookingAgentService agent) {
        this.agent = agent;
    }

    /** Send a message; omit conversationId to start a new conversation. */
    @PostMapping("/chat")
    public AgentResponse chat(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody AgentRequest request) {
        return agent.chat(user, request.conversationId(), request.message(), request.declineConfirmationId());
    }

    /** Reopen a conversation (e.g. after a page reload): visible messages, cards, and any pending confirmation. */
    @GetMapping("/conversations/{id}")
    public ConversationResponse conversation(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return agent.history(user, id);
    }
}
