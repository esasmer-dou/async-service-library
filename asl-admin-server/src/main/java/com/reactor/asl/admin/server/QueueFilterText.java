package com.reactor.asl.admin.server;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the filter text of a bulk replay or delete from the portal's form fields. The pod parses it with
 * {@code QueueEntryFilter}; this class only lets valid values through so a typo fails here, not on every replica.
 */
final class QueueFilterText {
    static final int MAX_PER_COMMAND = 10_000;
    private static final Set<String> STATES = Set.of("FAILED", "PENDING", "RETRY_WAIT");

    private QueueFilterText() {
    }

    /**
     * @param states comma separated, empty means FAILED
     * @param category error category, {@code -} for entries without one, empty for any
     * @param fromMillis queued at or after, {@code null} for no lower bound
     * @param toMillis queued at or before, {@code null} for no upper bound
     */
    static String build(String states, String category, Long fromMillis, Long toMillis) {
        Set<String> selected = new TreeSet<>();
        if (states != null) {
            for (String state : states.split(",")) {
                String name = state.trim().toUpperCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                if (!STATES.contains(name)) {
                    throw new IllegalArgumentException("Unknown state " + state + " (allowed: FAILED, PENDING, RETRY_WAIT)");
                }
                selected.add(name);
            }
        }
        if (selected.isEmpty()) {
            selected.add("FAILED");
        }
        StringBuilder text = new StringBuilder("states=").append(String.join(",", selected));
        if (category != null && !category.isBlank()) {
            String value = category.trim();
            if (value.indexOf(';') >= 0 || value.indexOf('=') >= 0 || value.length() > 100) {
                throw new IllegalArgumentException("Invalid category");
            }
            text.append(";category=").append(value);
        }
        if (fromMillis != null && toMillis != null && fromMillis > toMillis) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (fromMillis != null) {
            text.append(";from=").append(fromMillis);
        }
        if (toMillis != null) {
            text.append(";to=").append(toMillis);
        }
        return text.toString();
    }
}
