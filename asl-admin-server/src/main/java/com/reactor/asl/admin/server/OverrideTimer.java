package com.reactor.asl.admin.server;

/**
 * A timed override: at {@code expiresAtMillis} the method goes back to {@code previous}, the override that was in
 * place before the timed one, or to the application's own value when {@code previous} is {@code null}.
 * Setting another timed override keeps the original {@code previous}, so a chain of timed changes always ends where
 * it started.
 */
public record OverrideTimer(long expiresAtMillis, MethodSetting previous) {
    /** Shortest and longest duration accepted from the API. */
    public static final long MIN_SECONDS = 10;
    public static final long MAX_SECONDS = 7L * 24 * 3600;

    /** Store form: {@code expiresAt} then a tab and the previous setting's text (empty when there was none). */
    public String toText() {
        return expiresAtMillis + "\t" + (previous == null ? "" : previous.toText());
    }

    public static OverrideTimer parse(String text) {
        int tab = text.indexOf('\t');
        if (tab < 0) {
            throw new IllegalArgumentException("Invalid override timer: " + text);
        }
        long expiresAt = Long.parseLong(text.substring(0, tab));
        String previous = text.substring(tab + 1);
        return new OverrideTimer(expiresAt, previous.isEmpty() ? null : MethodSetting.parse(previous));
    }
}
