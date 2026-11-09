package com.cinebot.agent.tools;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Argument validation for tool calls. The JSON schema tells the model what to send, but a model can still send
 * "tomorrow" for a date or a negative id, so every argument is checked before it reaches a service.
 */
final class ToolArgs {

    private ToolArgs() {
    }

    static long requireId(Long value, String name) {
        if (value == null) {
            throw new InvalidToolArgumentException(name + " is required");
        }
        if (value <= 0) {
            throw new InvalidToolArgumentException(name + " must be a positive id from an earlier tool result");
        }
        return value;
    }

    static Long optionalId(Long value, String name) {
        return value == null ? null : requireId(value, name);
    }

    static String optionalText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidToolArgumentException(name + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    static String requireText(String value, String name, int maxLength) {
        String text = optionalText(value, name, maxLength);
        if (text == null) {
            throw new InvalidToolArgumentException(name + " is required");
        }
        return text;
    }

    static LocalDate optionalDate(String value, String name) {
        String text = optionalText(value, name, 10);
        try {
            return text == null ? null : LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new InvalidToolArgumentException(name + " must be an ISO date YYYY-MM-DD (resolve words like "
                    + "'tomorrow' using today's date from the instructions)");
        }
    }

    static LocalTime optionalTime(String value, String name) {
        String text = optionalText(value, name, 5);
        try {
            return text == null ? null : LocalTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new InvalidToolArgumentException(name + " must be a 24-hour time HH:mm, e.g. 19:30");
        }
    }

    static Integer optionalCount(Integer value, String name, int max) {
        if (value == null) {
            return null;
        }
        if (value < 1 || value > max) {
            throw new InvalidToolArgumentException(name + " must be between 1 and " + max);
        }
        return value;
    }

    static String optionalLanguage(String value) {
        String text = optionalText(value, "language", 2);
        if (text != null && !text.matches("^[A-Za-z]{2}$")) {
            throw new InvalidToolArgumentException("language must be a 2-letter ISO 639-1 code such as en, hi, ta");
        }
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }
}
