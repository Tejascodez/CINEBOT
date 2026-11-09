package com.cinebot.booking.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TicketCodecTest {

    private final TicketCodec codec = new TicketCodec("unit-test-ticket-secret-0123456789-xyz");

    @Test
    void roundTrips() {
        String qr = codec.encode("CB7K3M9Q2X", 42L);

        assertThat(qr).matches("^CBT1\\.CB7K3M9Q2X\\.42\\.[A-Za-z0-9_-]{22}$");
        assertThat(codec.decode(qr)).contains(new TicketCodec.Ticket("CB7K3M9Q2X", 42L));
    }

    @Test
    void rejectsTamperingAndOtherKeys() {
        String qr = codec.encode("CB7K3M9Q2X", 42L);

        assertThat(codec.decode(qr.replace(".42.", ".43."))).isEmpty();
        assertThat(codec.decode(qr.replace("CB7K3M9Q2X", "CB7K3M9Q2Y"))).isEmpty();
        assertThat(new TicketCodec("another-ticket-secret-0123456789-abcdef").decode(qr)).isEmpty();
        assertThat(codec.decode("garbage")).isEmpty();
        assertThat(codec.decode(null)).isEmpty();
    }

    @Test
    void requiresStrongSecret() {
        assertThatThrownBy(() -> new TicketCodec("short")).isInstanceOf(IllegalStateException.class);
    }
}
