package com.cinebot.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Dev/test: "sends" by logging. The notifications table keeps the full message for the UI and for tests. */
@Component
class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("EMAIL to={} subject=\"{}\"", to, subject);
    }
}
