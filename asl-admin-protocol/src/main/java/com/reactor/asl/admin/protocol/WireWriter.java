package com.reactor.asl.admin.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Append-only binary writer. Integers use unsigned LEB128 ({@link #uvar}) or ZigZag + LEB128
 * ({@link #svar}). Strings are written as {@code uvar(length + 1)} followed by UTF-8 bytes, so
 * {@code null} costs a single zero byte. Not thread-safe; reuse with {@link #reset()}.
 */
public final class WireWriter {
    private byte[] buffer;
    private int size;

    public WireWriter() {
        this(64);
    }

    public WireWriter(int initialCapacity) {
        this.buffer = new byte[Math.max(16, initialCapacity)];
    }

    public WireWriter reset() {
        size = 0;
        return this;
    }

    public int size() {
        return size;
    }

    public WireWriter u8(int value) {
        ensure(1);
        buffer[size++] = (byte) value;
        return this;
    }

    public WireWriter bool(boolean value) {
        return u8(value ? 1 : 0);
    }

    public WireWriter uvar(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("uvar requires a non-negative value: " + value);
        }
        ensure(10);
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            buffer[size++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer[size++] = (byte) remaining;
        return this;
    }

    public WireWriter svar(long value) {
        return uvarUnchecked((value << 1) ^ (value >> 63));
    }

    public WireWriter str(String value) {
        if (value == null) {
            return u8(0);
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > AslAdminProtocol.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("String exceeds " + AslAdminProtocol.MAX_STRING_BYTES + " bytes");
        }
        uvar(bytes.length + 1L);
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
        return this;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    private WireWriter uvarUnchecked(long value) {
        ensure(10);
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            buffer[size++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer[size++] = (byte) remaining;
        return this;
    }

    private void ensure(int extra) {
        if (size + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + extra));
        }
    }
}
