package com.reactor.asl.admin.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireTest {

    @Test
    void varintBoundaries() {
        long[] values = {0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE, Long.MAX_VALUE};
        WireWriter out = new WireWriter(1);
        for (long value : values) {
            out.uvar(value).svar(value).svar(-value);
        }
        out.svar(Long.MIN_VALUE);
        WireReader in = new WireReader(out.toByteArray());
        for (long value : values) {
            assertEquals(value, in.uvar());
            assertEquals(value, in.svar());
            assertEquals(-value, in.svar());
        }
        assertEquals(Long.MIN_VALUE, in.svar());
        in.expectEnd();
        assertEquals(1, new WireWriter().uvar(127).size());
        assertEquals(2, new WireWriter().uvar(128).size());
    }

    @Test
    void stringsIncludingNullAndEmpty() {
        WireWriter out = new WireWriter().str(null).str("").str("Çalışma ğüşiöç");
        WireReader in = new WireReader(out.toByteArray());
        assertNull(in.str());
        assertEquals("", in.str());
        assertEquals("Çalışma ğüşiöç", in.str());
    }

    @Test
    void rejectsNegativeUvarOversizedStringAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new WireWriter().uvar(-1));
        assertThrows(IllegalArgumentException.class, () -> new WireWriter().str("x".repeat(AslAdminProtocol.MAX_STRING_BYTES + 1)));
        byte[] tooLong = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01};
        assertThrows(AslProtocolException.class, () -> new WireReader(tooLong).uvar());
        byte[] elevenBytes = new byte[11];
        java.util.Arrays.fill(elevenBytes, (byte) 0x80);
        assertThrows(AslProtocolException.class, () -> new WireReader(elevenBytes).svar());
        assertThrows(AslProtocolException.class, () -> new WireReader(new WireWriter().uvar(1L << 40).toByteArray()).uint());
        assertThrows(AslProtocolException.class, () -> new WireReader(new byte[]{2}).bool());
        byte[] longString = new WireWriter().uvar(AslAdminProtocol.MAX_STRING_BYTES + 2L).toByteArray();
        assertThrows(AslProtocolException.class, () -> new WireReader(longString).str());
    }
}
