package com.cinebot.agent.tools;

import com.cinebot.agent.config.AgentProperties;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Server-side enforcement of "never book or cancel without the user's explicit confirmation".
 * <p>
 * A prompt instruction alone ("ask before booking") is a request to the model, not a guarantee. This gate makes
 * it a rule the model cannot break, because the confirm tools only work on a {@link PendingAction} that:
 * <ol>
 *   <li>was prepared by the backend (so the seats, price and booking are exactly what the user was shown),</li>
 *   <li>is identified by the one-time {@code confirmationId} the prepare step returned,</li>
 *   <li>was prepared in an EARLIER user turn: preparing and confirming in one model response is rejected,
 *       so there is always a user message between seeing the summary and the action,</li>
 *   <li>was not declined: a "No" button or a typed refusal withdraws it BEFORE the model runs
 *       ({@link #withdrawIfDeclined}), so the model cannot misread a "no" as a yes,</li>
 *   <li>has not expired and has not been used.</li>
 * </ol>
 */
@Component
public class ConfirmationGate {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray(); // no 0/O, 1/I/L

    /**
     * Typed refusals. Deliberately broad: a false positive only withdraws a free, read-only summary (the agent
     * asks again), while a false negative is what the gate exists to prevent. It never approves anything.
     */
    private static final Pattern REFUSAL_START = Pattern.compile(
            "^(no|nope|nah|nahi|nahin|stop|wait|dont|don't|not)\\b");
    private static final Pattern REFUSAL_ANYWHERE = Pattern.compile(
            "\\b((don't|dont|do not) (hold|book|cancel|do|go|proceed|confirm|want)|not now|not yet|never mind|"
                    + "nevermind|changed my mind|hold on|no thanks|no thank you)\\b");
    /** "No problem, go ahead" starts with "no" but agrees. */
    private static final Pattern AGREEING_NO = Pattern.compile("^no (problem|problems|worries|issue|issues)\\b");

    /** Thrown when a confirmation is not acceptable; mapped to an error result for the model. */
    public static final class Rejected extends RuntimeException {

        private final String code;

        Rejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final AgentProperties properties;
    private final SecureRandom random = new SecureRandom();

    public ConfirmationGate(AgentProperties properties) {
        this.properties = properties;
    }

    /** Stores the prepared action in the state, replacing any earlier unconfirmed one. */
    PendingAction prepare(AgentTurn turn, PendingAction.Type type, Long showId, List<Long> showSeatIds,
                          List<String> seatLabels, Long totalPaise, Long bookingId, String bookingReference) {
        PendingAction action = new PendingAction(newId(), type, turn.state().getTurn(),
                turn.now().plus(properties.confirmationTtl()).toString(),
                showId, showSeatIds, seatLabels, totalPaise, bookingId, bookingReference);
        turn.state().setPending(action);
        return action;
    }

    /**
     * Runs on the user's message BEFORE the model sees it. Withdraws the pending action when the user declined it:
     * <ul>
     *   <li>{@code declinedId} set (the card's "No" button): declines exactly that action, nothing else;</li>
     *   <li>otherwise, a typed message that reads as a refusal ("no", "don't", "not now"...).</li>
     * </ul>
     *
     * @return true if a pending action was withdrawn
     */
    public boolean withdrawIfDeclined(ConversationState state, String userMessage, String declinedId) {
        PendingAction pending = state.getPending();
        if (pending == null) {
            return false;
        }
        boolean declined = declinedId != null
                ? pending.id().equalsIgnoreCase(declinedId.trim())
                : looksLikeRefusal(userMessage);
        if (declined) {
            state.setPending(null);
            state.setDeclinedId(pending.id());
        }
        return declined;
    }

    static boolean looksLikeRefusal(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT).replace('’', '\'')
                .replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ").trim();
        if (REFUSAL_ANYWHERE.matcher(text).find()) {
            return true;
        }
        return REFUSAL_START.matcher(text).find() && !AGREEING_NO.matcher(text).find();
    }

    /**
     * Validates and CONSUMES the pending action: it is single use, so a failed execution is never retried as-is
     * (the model prepares a fresh summary). Call {@link #executed} once the action has actually succeeded.
     *
     * @return the action to execute, or empty if this exact confirmation was already EXECUTED (repeated "yes");
     *         the caller then reports {@link ConversationState#getLastConfirmed()}
     */
    Optional<PendingAction> consume(AgentTurn turn, String confirmationId, PendingAction.Type type) {
        ConversationState state = turn.state();
        String id = confirmationId == null ? "" : confirmationId.trim().toUpperCase(Locale.ROOT);
        ConversationState.Confirmed last = state.getLastConfirmed();
        if (last != null && last.id().equals(id) && last.type() == type) {
            return Optional.empty(); // idempotent: already done, do not do it twice
        }
        if (!id.isEmpty() && id.equals(state.getDeclinedId())) {
            throw new Rejected("USER_DECLINED", "The user declined this, so it was withdrawn and nothing was done. "
                    + "Do not prepare it again unless they ask; ask what they would like instead.");
        }
        PendingAction pending = state.getPending();
        if (pending == null || !pending.id().equals(id) || pending.type() != type) {
            throw new Rejected("NO_PENDING_CONFIRMATION", "There is nothing with confirmationId '" + confirmationId
                    + "' waiting for confirmation. Prepare the action first and show the summary to the user.");
        }
        if (pending.createdTurn() >= state.getTurn()) {
            throw new Rejected("USER_CONFIRMATION_REQUIRED", "The user has not confirmed yet. Show them the summary, "
                    + "ask for confirmation, and call this tool only after they explicitly agree in their reply.");
        }
        state.setPending(null);
        if (!turn.now().isBefore(Instant.parse(pending.expiresAt()))) {
            throw new Rejected("CONFIRMATION_EXPIRED", "That confirmation expired. Check availability again and "
                    + "prepare a fresh summary.");
        }
        return Optional.of(pending);
    }

    /** Records a SUCCESSFUL execution, so that a repeated confirmation reports this booking instead of acting. */
    void executed(AgentTurn turn, PendingAction action, long bookingId) {
        turn.state().setLastConfirmed(new ConversationState.Confirmed(action.id(), action.type(), bookingId));
    }

    private String newId() {
        char[] id = new char[8];
        for (int i = 0; i < id.length; i++) {
            id[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(id);
    }
}
