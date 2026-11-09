package com.cinebot.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The tests' LLM. Imported by IntegrationTest (one shared context for all ITs); the real provider is off in
 * tests (spring.ai.model.chat=none), so this is the only ChatModel and the agent uses it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedChatModelConfig {

    @Bean
    ScriptedChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }
}
