package com.cinebot.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The LLM side. The provider (Gemini) is whatever {@link ChatModel} Spring AI auto-configured; the agent only
 * ever talks to {@link ChatClient}. When no provider is configured there is no ChatModel, and the agent reports
 * AI_NOT_CONFIGURED instead of the application failing to start.
 */
@Configuration
public class AgentChatConfig {

    /** Wraps the optional ChatClient, so "AI is off" is an explicit, testable state rather than a null. */
    public record AgentLlm(ChatClient client) {

        public boolean configured() {
            return client != null;
        }
    }

    @Bean
    AgentLlm agentLlm(ObjectProvider<ChatModel> chatModel) {
        ChatModel model = chatModel.getIfAvailable();
        return new AgentLlm(model == null ? null : ChatClient.builder(model).build());
    }
}
