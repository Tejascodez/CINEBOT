package com.cinebot.agent.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Gemini SDK client, with a request timeout. Spring AI's auto-configured client has none (and the SDK's
 * OkHttp client does not use spring.http.client.*), so an overloaded model left the chat request hanging
 * indefinitely. Spring AI's client bean is @ConditionalOnMissingBean, so this one replaces it.
 *
 * <p>Retries: the SDK's own retry is turned off (attempts = 1); spring.ai.retry is the single retry policy, so
 * the worst case for one model call stays bounded (max-attempts x llm-timeout).
 *
 * <p>Only active when the Gemini provider is enabled (AI_CHAT_PROVIDER=google-genai). API-key mode only, which
 * is what CineBot uses; switching to Vertex AI would need project/location/credentials here.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "google-genai")
public class GeminiClientConfig {

    @Bean
    Client googleGenAiClient(@Value("${spring.ai.google.genai.api-key}") String apiKey, AgentProperties properties) {
        HttpOptions http = HttpOptions.builder()
                .timeout(Math.toIntExact(properties.llmTimeout().toMillis()))
                .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                .build();
        return Client.builder().apiKey(apiKey).httpOptions(http).build();
    }
}
