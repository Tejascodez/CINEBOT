package com.cinebot.booking;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Human-friendly, unguessable reference like "CB7K3M9Q2X". The alphabet drops look-alikes (0/O, 1/I/L, U),
 * because people read these aloud at the counter. 30^8 ≈ 6.5e11 combinations: random, so not enumerable like
 * sequential ids. Collisions are checked, with the unique constraint as the final guard.
 */
@Component
class BookingReferenceGenerator {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    String next() {
        StringBuilder sb = new StringBuilder("CB");
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
