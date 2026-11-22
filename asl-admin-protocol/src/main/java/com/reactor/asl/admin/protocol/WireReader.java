package com.reactor.asl.admin.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Bounds-checked reader for {@link WireWriter} output. Every read validates the remaining input,
 * so a truncated or hostile message fails with {@link AslProtocolException} and never allocates
 * more than the message itself.
 */
public final class WireReader {
    private final byte[] data;
    private int position;

    public WireReader(byte[] data) {
        this.data = data == null ? new byte[0] : data;
    }

    public int u8() {
        require(1);
        return data[position++] & 0xFF;
    }

    public boolean bool() {
        int value = u8();
        if (value > 1) {
            throw new AslProtocolException("Invalid boolean: " + value);
        }
        return value == 1;
    }

    public long uvar() {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = u8();
            if (shift == 63 && b != 0) {
                throw new AslProtocolException("Varint overflow");
            }
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new AslProtocolException("Varint longer than 10 bytes");
    }

    /** Reads a non-negative value that must fit in an int. */
    public int uint() {
        long value = uvar();
        if (value > Integer.MAX_VALUE || value < 0) {
            throw new AslProtocolException("Value out of int range: " + value);
        }
        return (int) value;
    }

    /** Reads a repeated-field count, rejecting counts larger than the protocol limit or the input. */
    public int count() {
        int value = uint();
        if (value > AslAdminProtocol.MAX_COUNT || value > remaining()) {
            throw new AslProtocolException("Count out of range: " + value);
        }
        return value;
    }

    public long svar() {
        long raw = uvarRaw();
        return (raw >>> 1) ^ -(raw & 1);
    }

    public String str() {
        long encoded = uvar();
        if (encoded == 0) {
            return null;
        }
        long length = encoded - 1;
        if (length > AslAdminProtocol.MAX_STRING_BYTES) {
            throw new AslProtocolException("String too long: " + length);
        }
        require((int) length);
        String value = new String(data, position, (int) length, StandardCharsets.UTF_8);
        position += (int) length;
        return value;
    }

    public int remaining() {
        return data.length - position;
    }

    public void expectEnd() {
        if (remaining() != 0) {
            throw new AslProtocolException("Unexpected trailing bytes: " + remaining());
        }
    }

    private long uvarRaw() {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = u8();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new AslProtocolException("Varint longer than 10 bytes");
    }

    private void require(int bytes) {
        if (bytes < 0 || position + bytes > data.length) {
            throw new AslProtocolException("Truncated message");
        }
    }
}
