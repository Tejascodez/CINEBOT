package com.cinebot.payment;

import static com.cinebot.payment.PaymentStatus.CAPTURED;
import static com.cinebot.payment.PaymentStatus.CREATED;
import static com.cinebot.payment.PaymentStatus.EXPIRED;
import static com.cinebot.payment.PaymentStatus.FAILED;
import static com.cinebot.payment.PaymentStatus.REFUNDED;
import static com.cinebot.payment.PaymentStatus.REFUND_PENDING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentStatusTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED = Map.of(
            CREATED, EnumSet.of(CAPTURED, FAILED, EXPIRED),
            FAILED, EnumSet.of(CAPTURED),        // late capture after a failed attempt
            EXPIRED, EnumSet.of(CAPTURED),       // late capture after the order window
            CAPTURED, EnumSet.of(REFUND_PENDING),
            REFUND_PENDING, EnumSet.of(REFUNDED),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void transitionsMatchDesign(PaymentStatus from) {
        for (PaymentStatus to : PaymentStatus.values()) {
            assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(EXPECTED.get(from).contains(to));
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CAPTURED", "REFUND_PENDING", "REFUNDED"})
    void moneyReceivedStates(PaymentStatus status) {
        assertThat(status.isMoneyReceived()).isTrue();
    }
}
