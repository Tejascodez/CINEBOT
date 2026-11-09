package com.cinebot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// UserDetailsServiceAutoConfiguration is excluded: we authenticate with our own JWT filter,
// so Spring's generated in-memory user ("Using generated security password") must not exist.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class CineBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CineBotApplication.class, args);
    }
}
