package com.cinebot.agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Structured memory of the booking journey, persisted per conversation (JSONB) and shown to the model at the
 * start of every turn. Chat history alone is not enough: history is windowed (old messages drop out), and a
 * model re-reading 40 messages to find "the show we picked" is slower and less reliable than one state block.
 * <p>
 * Only TOOLS write this state, from backend results: the model cannot "remember" a show id that the backend never
 * returned. It holds ids and labels only; availability, prices and booking status are always re-read from the
 * backend before anything is decided.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationState {

    private Long movieId;
    private String movieTitle;
    private String city;
    private String date;            // ISO yyyy-MM-dd, India time
    private Long theatreId;
    private String theatreName;
    private Long showId;
    private String showDescription; // "Interstellar · PVR Velachery · Thu 5 Nov, 7:00 PM"
    private Integer ticketCount;
    private List<String> seats = new ArrayList<>();
    private UUID lockId;
    private Long bookingId;
    private String bookingReference;
    private String bookingStatus;
    private PendingAction pending;
    private Confirmed lastConfirmed; // the most recently EXECUTED confirmation: a repeated "yes" is a no-op
    private String declinedId;       // the most recently DECLINED confirmation: confirming it is refused
    private int turn;                // number of user messages so far; used by the confirmation gate

    /** A confirmation that was carried out successfully, and the booking it held or cancelled. */
    public record Confirmed(String id, PendingAction.Type type, long bookingId) {
    }

    // --- transitions used by tools (each keeps the state consistent: a new movie invalidates the old show) ---

    public void selectMovie(Long id, String title) {
        if (movieId != null && !movieId.equals(id)) {
            clearShow();
            theatreId = null;
            theatreName = null;
        }
        movieId = id;
        movieTitle = title;
    }

    public void selectCity(String newCity) {
        if (city != null && !city.equalsIgnoreCase(newCity)) {
            theatreId = null;
            theatreName = null;
            clearShow();
        }
        city = newCity;
    }

    public void selectTheatre(Long id, String name) {
        theatreId = id;
        theatreName = name;
    }

    public void selectShow(Long id, String description) {
        if (showId != null && !showId.equals(id)) {
            seats = new ArrayList<>();
            if (pending != null && pending.type() == PendingAction.Type.HOLD_SEATS) {
                pending = null; // a prepared hold for another show must never be confirmed for this one
            }
        }
        showId = id;
        showDescription = description;
    }

    private void clearShow() {
        showId = null;
        showDescription = null;
        seats = new ArrayList<>();
        if (pending != null && pending.type() == PendingAction.Type.HOLD_SEATS) {
            pending = null;
        }
    }

    public void recordBooking(Long id, String reference, String status, UUID lock, List<String> heldSeats) {
        bookingId = id;
        bookingReference = reference;
        bookingStatus = status;
        lockId = lock;
        seats = new ArrayList<>(heldSeats);
    }

    public void updateBookingStatus(Long id, String reference, String status) {
        if (id.equals(bookingId)) {
            bookingStatus = status;
            bookingReference = reference;
        }
    }

    /** Renders the state for the system prompt: what the model may treat as already decided. */
    public String describeForModel() {
        List<String> lines = new ArrayList<>();
        add(lines, "movie", movieTitle == null ? null : movieTitle + " (movieId " + movieId + ")");
        add(lines, "city", city);
        add(lines, "date", date);
        add(lines, "theatre", theatreName == null ? null : theatreName + " (theatreId " + theatreId + ")");
        add(lines, "show", showDescription == null ? null : showDescription + " (showId " + showId + ")");
        add(lines, "tickets", ticketCount == null ? null : ticketCount.toString());
        add(lines, "seats", seats.isEmpty() ? null : String.join(", ", seats));
        add(lines, "booking", bookingId == null ? null
                : bookingReference + " (bookingId " + bookingId + "), status " + bookingStatus);
        if (pending != null) {
            lines.add("- awaiting user confirmation: " + pending.describe() + " (confirmationId " + pending.id() + ")");
        }
        return lines.isEmpty() ? "(nothing selected yet)" : String.join("\n", lines);
    }

    private static void add(List<String> lines, String label, String value) {
        if (value != null) {
            lines.add("- " + label + ": " + value);
        }
    }

    @JsonIgnore
    public boolean isEmpty() {
        return movieId == null && city == null && showId == null && bookingId == null && pending == null;
    }

    // --- plain accessors (Jackson / JSONB mapping) ---

    public Long getMovieId() { return movieId; }
    public void setMovieId(Long movieId) { this.movieId = movieId; }
    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public Long getTheatreId() { return theatreId; }
    public void setTheatreId(Long theatreId) { this.theatreId = theatreId; }
    public String getTheatreName() { return theatreName; }
    public void setTheatreName(String theatreName) { this.theatreName = theatreName; }
    public Long getShowId() { return showId; }
    public void setShowId(Long showId) { this.showId = showId; }
    public String getShowDescription() { return showDescription; }
    public void setShowDescription(String showDescription) { this.showDescription = showDescription; }
    public Integer getTicketCount() { return ticketCount; }
    public void setTicketCount(Integer ticketCount) { this.ticketCount = ticketCount; }
    public List<String> getSeats() { return seats; }
    public void setSeats(List<String> seats) { this.seats = seats == null ? new ArrayList<>() : new ArrayList<>(seats); }
    public UUID getLockId() { return lockId; }
    public void setLockId(UUID lockId) { this.lockId = lockId; }
    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
    public String getBookingReference() { return bookingReference; }
    public void setBookingReference(String bookingReference) { this.bookingReference = bookingReference; }
    public String getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(String bookingStatus) { this.bookingStatus = bookingStatus; }
    public PendingAction getPending() { return pending; }
    public void setPending(PendingAction pending) { this.pending = pending; }
    public Confirmed getLastConfirmed() { return lastConfirmed; }
    public void setLastConfirmed(Confirmed lastConfirmed) { this.lastConfirmed = lastConfirmed; }
    public String getDeclinedId() { return declinedId; }
    public void setDeclinedId(String declinedId) { this.declinedId = declinedId; }
    public int getTurn() { return turn; }
    public void setTurn(int turn) { this.turn = turn; }
}
