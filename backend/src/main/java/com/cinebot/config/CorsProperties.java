package com.cinebot.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cinebot.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
