package com.reactor.asl.admin.protocol;

import com.reactor.asl.admin.protocol.AslAdminMessages.Ack;
import com.reactor.asl.admin.protocol.AslAdminMessages.Beat;
import com.reactor.asl.admin.protocol.AslAdminMessages.BeatReply;
import com.reactor.asl.admin.protocol.AslAdminMessages.Command;
import com.reactor.asl.admin.protocol.AslAdminMessages.ConfigBlock;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodInfo;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodOverride;
import com.reactor.asl.admin.protocol.AslAdminMessages.MethodSample;
import com.reactor.asl.admin.protocol.AslAdminMessages.QueueEntry;
import com.reactor.asl.admin.protocol.AslAdminMessages.Register;
import com.reactor.asl.admin.protocol.AslAdminMessages.RegisterReply;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslAdminCodecTest {

    @Test
    void idleBeatIsTiny() {
        Beat beat = new Beat(42, 300, 7, 0, null, List.of(), List.of());
        byte[] body = AslAdminCodec.encode(beat);
        assertArrayEquals(new byte[]{0x01, 0x03, 0x2A, (byte) 0xAC, 0x02, 0x07, 0x00, 0x00, 0x00}, body);
        assertTrue(body.length <= 32);
        assertEquals(beat, AslAdminCodec.decodeBeat(body));
    }

    @Test
    void beatRoundTripsSamplesAcksAndConfigError() {
        long[] values = new long[MethodSample.FIELD_COUNT];
        values[MethodSample.STATE] = MethodSample.STATE_ENABLED | MethodSample.STATE_ASYNC;
        values[MethodSample.MAX_CONCURRENCY] = Integer.MAX_VALUE;
        values[MethodSample.SUCCESS] = 1_234_567_890_123L;
        values[MethodSample.QUEUE_PENDING] = 17;
        int mask = (1 << MethodSample.STATE) | (1 << MethodSample.MAX_CONCURRENCY)
                | (1 << MethodSample.SUCCESS) | (1 << MethodSample.QUEUE_PENDING);
        MethodSample sample = new MethodSample(3, mask, values);
        Ack ack = new Ack(9, Ack.OK, 2, null, List.of(
                new QueueEntry("e1", "FAILED", 3, 1, 1_700_000_000_000L, "TIMEOUT", "boom ü")
        ));
        Beat beat = new Beat(Integer.MAX_VALUE, 1, 0, Beat.FULL, "Unknown method", List.of(ack), List.of(sample));

        Beat decoded = AslAdminCodec.decodeBeat(AslAdminCodec.encode(beat));

        assertEquals(beat, decoded);
        assertTrue(decoded.full());
        assertFalse(decoded.leaving());
        assertEquals(1_234_567_890_123L, decoded.samples().get(0).value(MethodSample.SUCCESS));
        assertFalse(decoded.samples().get(0).has(MethodSample.IN_FLIGHT));
        assertEquals("boom ü", decoded.acks().get(0).entries().get(0).lastError());
    }

    @Test
    void registerRoundTrips() {
        Register register = new Register("orders", "orders-7f9c", "0.2.0", List.of(
                new MethodInfo("OrderService", "create(java.lang.String)", true, true),
                new MethodInfo("OrderService", "find(long)", false, false)
        ));
        assertEquals(register, AslAdminCodec.decodeRegister(AslAdminCodec.encode(register)));
    }

    @Test
    void registerReplyWithAndWithoutConfig() {
        ConfigBlock config = new ConfigBlock(5, List.of(
                new MethodOverride(0, false, true, 20, 4),
                new MethodOverride(1, true, null, null, null),
                new MethodOverride(1, false, null, 3, null, true)
        ));
        RegisterReply withConfig = new RegisterReply(77, 15_000, config);
        RegisterReply withoutConfig = new RegisterReply(78, 15_000, null);
        assertEquals(withConfig, AslAdminCodec.decodeRegisterReply(AslAdminCodec.encode(withConfig)));
        assertNull(AslAdminCodec.decodeRegisterReply(AslAdminCodec.encode(withoutConfig)).config());
    }

    @Test
    void beatReplyRoundTrips() {
        BeatReply reply = new BeatReply(true, 2_000,
                new ConfigBlock(3, List.of(new MethodOverride(2, null, false, null, 0))),
                List.of(new Command(11, Command.BROWSE_QUEUE, 2, null, 20),
                        new Command(12, Command.REPLAY_ENTRY, 2, "42", 0)));
        assertFalse(reply.isEmpty());
        assertEquals(reply, AslAdminCodec.decodeBeatReply(AslAdminCodec.encode(reply)));
        assertTrue(BeatReply.empty().isEmpty());
        assertEquals(BeatReply.empty(), AslAdminCodec.decodeBeatReply(AslAdminCodec.encode(BeatReply.empty())));
    }

    @Test
    void rejectsWrongVersionTypeTruncationAndTrailingBytes() {
        byte[] body = AslAdminCodec.encode(new Beat(1, 1, 0, 0, null, List.of(), List.of()));
        byte[] wrongVersion = body.clone();
        wrongVersion[0] = 9;
        assertThrows(AslProtocolException.class, () -> AslAdminCodec.decodeBeat(wrongVersion));
        assertThrows(AslProtocolException.class, () -> AslAdminCodec.decodeRegister(body));
        assertThrows(AslProtocolException.class, () -> AslAdminCodec.decodeBeat(Arrays.copyOf(body, body.length - 1)));
        assertThrows(AslProtocolException.class, () -> AslAdminCodec.decodeBeat(Arrays.copyOf(body, body.length + 1)));
        assertThrows(AslProtocolException.class, () -> AslAdminCodec.decodeBeat(null));
    }

    @Test
    void hostileCountsAreRejectedWithoutAllocation() {
        WireWriter out = new WireWriter().u8(1).u8(AslAdminCodec.TYPE_BEAT).uvar(1).uvar(1).uvar(0).u8(0)
                .uvar(Integer.MAX_VALUE);
        assertThrows(AslProtocolException.class, () -> AslAdminCodec.decodeBeat(out.toByteArray()));
    }

    @Test
    void unknownSampleFieldsAreRejected() {
        WireWriter out = new WireWriter().u8(1).u8(AslAdminCodec.TYPE_BEAT).uvar(1).uvar(1).uvar(0).u8(0)
                .uvar(0).uvar(1).uvar(0).uvar(1 << MethodSample.FIELD_COUNT);
        assertThrows(AslProtocolException.class, () -> AslAdminCodec.decodeBeat(out.toByteArray()));
    }

    @Test
    void blankApplicationIsRejected() {
        byte[] body = new WireWriter().u8(1).u8(AslAdminCodec.TYPE_REGISTER).str(" ").str("i").str(null).uvar(0).toByteArray();
        assertThrows(AslProtocolException.class, () -> AslAdminCodec.decodeRegister(body));
    }
}
