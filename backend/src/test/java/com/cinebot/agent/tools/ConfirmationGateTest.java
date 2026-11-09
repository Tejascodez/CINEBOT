package com.cinebot.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The typed-refusal check that withdraws a pending hold/cancellation before the model reads the reply. */
class ConfirmationGateTest {

    @ParameterizedTest
    @ValueSource(strings = {"no", "No.", "NO!", "nope", "nah", "No, don't hold them", "No, keep my booking",
            "no B3 instead", "don't", "Dont book it", "please don't hold them", "Wait", "stop", "not now",
            "Not yet, let me check", "never mind", "I changed my mind", "hold on a sec", "no thanks", "not sure",
            "nahi", "Don’t cancel it", "I do not want it"})
    void refusalsAndHesitationsWithdraw(String message) {
        assertThat(ConfirmationGate.looksLikeRefusal(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "Yes, hold these seats", "Yes, cancel this booking", "go ahead", "book it",
            "sure", "ok", "No problem, go ahead", "no worries, hold them", "yes please, I don't mind",
            "noted, yes", "Nothing else, yes book it", "yes cancel it"})
    void agreementsDoNot(String message) {
        assertThat(ConfirmationGate.looksLikeRefusal(message)).isFalse();
    }
}
