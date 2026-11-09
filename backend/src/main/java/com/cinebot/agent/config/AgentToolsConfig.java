package com.cinebot.agent.config;

import com.cinebot.agent.tools.BookingTools;
import com.cinebot.agent.tools.GuardedToolCallback;
import com.cinebot.agent.tools.MovieTools;
import com.cinebot.agent.tools.SeatTools;
import com.cinebot.agent.tools.ShowtimeTools;
import com.cinebot.agent.tools.TheatreTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns the {@code @Tool} methods into Spring AI {@link ToolCallback}s. Each callback carries a ToolDefinition
 * (name + description + JSON schema generated from the method parameters): that definition is ALL the model
 * knows about a tool, and it is what the model reasons over when it decides which tool to call.
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentToolsConfig {

    /** The complete, fixed tool set of the agent. Nothing outside this list can ever be invoked by the model. */
    public record AgentToolbox(List<ToolCallback> callbacks) {

        public List<String> names() {
            return callbacks.stream().map(c -> c.getToolDefinition().name()).toList();
        }
    }

    @Bean
    AgentToolbox agentToolbox(MovieTools movieTools, TheatreTools theatreTools, ShowtimeTools showtimeTools,
                              SeatTools seatTools, BookingTools bookingTools,
                              ObjectMapper json) {
        List<ToolCallback> callbacks = Arrays.stream(
                        ToolCallbacks.from(movieTools, theatreTools, showtimeTools, seatTools, bookingTools))
                .map(c -> (ToolCallback) new GuardedToolCallback(c, json))
                .toList();
        return new AgentToolbox(callbacks);
    }
}
