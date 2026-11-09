package com.cinebot.booking.ticket;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * QR ticket payload: {@code CBT1.<reference>.<bookingId>.<signature>}. The HMAC makes a ticket
 * unforgeable offline (a guessed reference is useless without the signature), while the gate still checks the
 * booking's live status, so a refunded ticket stops working. The frontend renders this string as a QR code.
 */
@Component
public class TicketCodec {

    private static final String VERSION = "CBT1";
    private static final Pattern FORMAT = Pattern.compile("^CBT1\\.(CB[0-9A-Z]{8})\\.(\\d{1,19})\\.([A-Za-z0-9_-]{22})$");

    private final SecretKeySpec key;

    public TicketCodec(@Value("${cinebot.ticket.secret}") String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("cinebot.ticket.secret must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String encode(String reference, Long bookingId) {
        return VERSION + "." + reference + "." + bookingId + "." + sign(reference + "." + bookingId);
    }

    public record Ticket(String reference, Long bookingId) {
    }

    public Optional<Ticket> decode(String payload) {
        var m = payload == null ? null : FORMAT.matcher(payload.trim());
        if (m == null || !m.matches()) {
            return Optional.empty();
        }
        String expected = sign(m.group(1) + "." + m.group(2));
        boolean valid = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                m.group(3).getBytes(StandardCharsets.US_ASCII)); // constant-time
        return valid ? Optional.of(new Ticket(m.group(1), Long.valueOf(m.group(2)))) : Optional.empty();
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] full = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(full, 16)); // 128-bit tag
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
