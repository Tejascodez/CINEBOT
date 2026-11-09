package com.cinebot.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxToolRounds   model -> tools -> model round trips allowed for ONE user message (runaway-loop guard)
 * @param historySize     stored messages replayed to the model as conversation memory
 * @param confirmationTtl how long a prepared hold / cancellation stays confirmable
 * @param llmTimeout      max time for ONE call to the LLM provider; a slow/overloaded provider fails the turn
 *                        with AI_UNAVAILABLE instead of hanging the request
 */
@Validated
@ConfigurationProperties(prefix = "cinebot.agent")
public record AgentProperties(@Min(1) @Max(10) int maxToolRounds,
                              @Min(6) @Max(200) int historySize,
                              @NotNull Duration confirmationTtl,
                              @NotNull Duration llmTimeout) {
}
