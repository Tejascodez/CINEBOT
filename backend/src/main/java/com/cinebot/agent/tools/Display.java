package com.cinebot.agent.tools;

import com.cinebot.common.time.BusinessTime;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats values the way the model should repeat them to the user: rupees (not paise) and India time.
 * Pre-formatting removes a whole class of model mistakes (e.g. "₹40000" for 40000 paise, or UTC times).
 */
final class Display {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private Display() {
    }

    static String rupees(long paise) {
        return paise % 100 == 0 ? "₹" + paise / 100 : String.format(Locale.ROOT, "₹%d.%02d", paise / 100, paise % 100);
    }

    static String when(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(BusinessTime.ZONE).format(WHEN);
    }

    static String time(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(BusinessTime.ZONE).format(TIME);
    }

    static String date(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(BusinessTime.ZONE).toLocalDate().toString();
    }

    static String date(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
