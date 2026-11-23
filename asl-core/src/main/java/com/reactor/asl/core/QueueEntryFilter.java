package com.reactor.asl.core;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Selects queue entries for a bulk replay or delete.
 *
 * <p>Text form (sent by the admin server): {@code states=FAILED,PENDING;category=TIMEOUT;from=1700000000000;to=1700003600000}.
 * Every key is optional; the states default to {@code FAILED}. {@code from}/{@code to} are epoch milliseconds of the
 * time the call was queued, both inclusive. {@code category} is compared without regard to case; {@code -} selects
 * entries without a category. Entries that are running ({@code IN_PROGRESS}) are never selected.</p>
 *
 * @param max how many entries to change at most; between 1 and {@link #MAX_PER_COMMAND}
 */
public record QueueEntryFilter(Set<String> states, String errorCategory, long fromMillis, long toMillis, int max) {
    /** Upper bound of entries changed by one bulk command, so one command cannot hold the queue for long. */
    public static final int MAX_PER_COMMAND = 10_000;
    /** States a bulk command may select. */
    public static final Set<String> SELECTABLE_STATES = Set.of("FAILED", "PENDING", "RETRY_WAIT");
    /** {@code category} value that selects entries without a category. */
    public static final String NO_CATEGORY = "-";

    public QueueEntryFilter {
        states = states == null || states.isEmpty() ? Set.of("FAILED") : Set.copyOf(states);
        for (String state : states) {
            if (!SELECTABLE_STATES.contains(state)) {
                throw new IllegalArgumentException("State cannot be selected: " + state + " (allowed: FAILED, PENDING, RETRY_WAIT)");
            }
        }
        errorCategory = errorCategory == null || errorCategory.isBlank() ? null : errorCategory.trim();
        if (fromMillis > toMillis) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (max < 1 || max > MAX_PER_COMMAND) {
            throw new IllegalArgumentException("max must be between 1 and " + MAX_PER_COMMAND);
        }
    }

    public static QueueEntryFilter parse(String text, int max) {
        Set<String> states = null;
        String category = null;
        long from = Long.MIN_VALUE;
        long to = Long.MAX_VALUE;
        if (text != null) {
            for (String part : text.split(";")) {
                if (part.isBlank()) {
                    continue;
                }
                int eq = part.indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException("Invalid filter part: " + part);
                }
                String key = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();
                switch (key) {
                    case "states" -> states = new TreeSet<>(Arrays.stream(value.split(","))
                            .map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList());
                    case "category" -> category = value;
                    case "from" -> from = Long.parseLong(value);
                    case "to" -> to = Long.parseLong(value);
                    default -> throw new IllegalArgumentException("Unknown filter key: " + key);
                }
            }
        }
        return new QueueEntryFilter(states, category, from, to, max);
    }

    public boolean includesState(String state) {
        return states.contains(state);
    }

    public boolean matches(String state, String category, long enqueuedAtMillis) {
        if (!states.contains(state) || enqueuedAtMillis < fromMillis || enqueuedAtMillis > toMillis) {
            return false;
        }
        if (errorCategory == null) {
            return true;
        }
        if (NO_CATEGORY.equals(errorCategory)) {
            return category == null || category.isEmpty();
        }
        return errorCategory.equalsIgnoreCase(category);
    }
}
