package com.cinebot.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN account from configuration (dev: admin@cinebot.dev; prod: env vars / secret manager).
 * Self-registration can never create admins, so this is the only way in. No-op if unset or already present.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    AdminBootstrap(UserService userService, PasswordEncoder passwordEncoder,
                   @Value("${cinebot.bootstrap-admin.email:}") String email,
                   @Value("${cinebot.bootstrap-admin.password:}") String password) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank() || userService.findByEmail(email).isPresent()) {
            return;
        }
        userService.createUser(email, passwordEncoder.encode(password), "CineBot Admin", null, Role.ADMIN);
        log.info("Bootstrap admin account created: {}", email);
    }
}
