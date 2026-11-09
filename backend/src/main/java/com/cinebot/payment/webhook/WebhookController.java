package com.cinebot.payment.webhook;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public (no JWT): authenticity comes from the HMAC signature header instead. */
@RestController
@RequestMapping("/api/v1/payments/webhook")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /** Body taken as a raw String: the signature covers exact bytes. */
    @PostMapping
    public Map<String, String> receive(@RequestBody String rawBody,
                                       @RequestHeader(name = "X-Cinebot-Signature", required = false) String signature) {
        return Map.of("status", webhooks.handle(rawBody, signature).name());
    }
}
