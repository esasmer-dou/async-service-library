package com.reactor.asl.admin.server;

/** Minimal JSON writer for the UI responses; the server never parses JSON. */
final class Json {
    private final StringBuilder out = new StringBuilder(512);
    private boolean needsComma;

    Json beginObject() {
        separator();
        out.append('{');
        needsComma = false;
        return this;
    }

    Json endObject() {
        out.append('}');
        needsComma = true;
        return this;
    }

    Json beginArray() {
        separator();
        out.append('[');
        needsComma = false;
        return this;
    }

    Json endArray() {
        out.append(']');
        needsComma = true;
        return this;
    }

    Json name(String name) {
        separator();
        string(name);
        out.append(':');
        needsComma = false;
        return this;
    }

    Json value(String value) {
        separator();
        if (value == null) {
            out.append("null");
        } else {
            string(value);
        }
        needsComma = true;
        return this;
    }

    Json value(long value) {
        separator();
        out.append(value);
        needsComma = true;
        return this;
    }

    Json value(Boolean value) {
        separator();
        out.append(value == null ? "null" : value.toString());
        needsComma = true;
        return this;
    }

    Json value(Integer value) {
        separator();
        out.append(value == null ? "null" : value.toString());
        needsComma = true;
        return this;
    }

    Json field(String name, String value) {
        return name(name).value(value);
    }

    Json field(String name, long value) {
        return name(name).value(value);
    }

    Json field(String name, Boolean value) {
        return name(name).value(value);
    }

    Json field(String name, Integer value) {
        return name(name).value(value);
    }

    Json setting(String name, MethodSetting setting) {
        return name(name).settingValue(setting);
    }

    Json settingValue(MethodSetting setting) {
        if (setting == null) {
            return value((String) null);
        }
        return beginObject().field("enabled", setting.enabled()).field("async", setting.async())
                .field("maxConcurrency", setting.maxConcurrency()).field("consumerThreads", setting.consumerThreads())
                .field("rateLimit", setting.rateLimit()).endObject();
    }

    /** Relative time, so the browser's clock does not matter: {@code {"expiresInMillis":…,"revertsTo":setting|null}}. */
    Json timer(String name, OverrideTimer timer, long now) {
        return name(name).timerValue(timer, now);
    }

    Json timerValue(OverrideTimer timer, long now) {
        if (timer == null) {
            return value((String) null);
        }
        return beginObject().field("expiresInMillis", Math.max(0L, timer.expiresAtMillis() - now))
                .setting("revertsTo", timer.previous()).endObject();
    }

    @Override
    public String toString() {
        return out.toString();
    }

    private void separator() {
        if (needsComma) {
            out.append(',');
            needsComma = false;
        }
    }

    private void string(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
