package com.reactor.asl.mapdb;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueueRecordCodecTest {
    private final QueueRecordCodec codec = new QueueRecordCodec(1_024);

    @Test
    void roundTripsVersionedQueueRecord() {
        QueueRecord source = record(new byte[]{1, 2, 3});

        QueueRecord decoded = codec.decode(codec.encode(source));

        assertEquals(source.id(), decoded.id());
        assertEquals(source.laneId(), decoded.laneId());
        assertEquals(source.codecId(), decoded.codecId());
        assertEquals(source.state(), decoded.state());
        assertEquals(source.idempotencyKey(), decoded.idempotencyKey());
        assertArrayEquals(source.payload(), decoded.payload());
    }

    @Test
    void rejectsUnknownVersionTruncationTrailingBytesAndOversizedPayload() {
        byte[] encoded = codec.encode(record(new byte[]{1, 2, 3}));

        byte[] wrongVersion = encoded.clone();
        wrongVersion[5] = 9;
        assertThrows(IllegalStateException.class, () -> codec.decode(wrongVersion));
        assertThrows(IllegalStateException.class, () -> codec.decode(Arrays.copyOf(encoded, encoded.length - 1)));

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalStateException.class, () -> codec.decode(trailing));

        QueueRecordCodec bounded = new QueueRecordCodec(2);
        assertThrows(IllegalStateException.class, () -> bounded.decode(encoded));
    }

    @Test
    void roundTripsInterruptionsAndDecodesLegacyFormatWithoutThem() throws Exception {
        QueueRecord interrupted = record(new byte[]{9}).interrupted("stopped", "x.Type", "INTERRUPTED");
        assertEquals(1, codec.decode(codec.encode(interrupted)).interruptions());

        // Build a format-1 record: same layout without the interruptions int.
        byte[] current = codec.encode(record(new byte[]{4, 5}));
        int payloadLengthOffset = current.length - 2 - Integer.BYTES;
        byte[] legacy = new byte[current.length - Integer.BYTES];
        System.arraycopy(current, 0, legacy, 0, payloadLengthOffset - Integer.BYTES);
        System.arraycopy(current, payloadLengthOffset, legacy, payloadLengthOffset - Integer.BYTES,
                current.length - payloadLengthOffset);
        legacy[5] = 1;

        QueueRecord decoded = codec.decode(legacy);
        assertEquals(0, decoded.interruptions());
        assertArrayEquals(new byte[]{4, 5}, decoded.payload());
        assertEquals("failure", decoded.lastError());
    }

    private static QueueRecord record(byte[] payload) {
        return new QueueRecord(
                7L,
                3,
                "test-codec-v1",
                payload,
                "summary",
                "example.Type",
                "v1",
                "order-42",
                AsyncInvocationState.RETRY_WAIT,
                100L,
                200L,
                0L,
                0L,
                2,
                "failure",
                IllegalStateException.class.getName(),
                "BUSINESS"
        );
    }
}
