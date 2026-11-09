package com.cinebot.notification;

/** Outbound channel. A real implementation (SES, SendGrid) would replace the logging one. */
public interface EmailSender {

    void send(String to, String subject, String body);
}
