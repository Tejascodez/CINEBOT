package com.cinebot.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Injecting a Clock (instead of calling Instant.now()) lets tests control time: token expiry, lock TTLs, etc. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
