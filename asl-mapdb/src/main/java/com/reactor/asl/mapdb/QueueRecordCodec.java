package com.reactor.asl.mapdb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class QueueRecordCodec {
    private static final int MAGIC = 0x41534c51;
    private static final short FORMAT_VERSION = 2;
    private static final short LEGACY_FORMAT_VERSION = 1;
    private static final int MAX_METADATA_BYTES = 256 * 1024;

    private final long maxPayloadBytes;

    QueueRecordCodec(long maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    byte[] encode(QueueRecord record) {
        int initialSize = Math.toIntExact(Math.min(Integer.MAX_VALUE, 256L + record.payload().length));
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(initialSize);
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeShort(FORMAT_VERSION);
            output.writeLong(record.id());
            output.writeInt(record.laneId());
            output.writeByte(record.state().ordinal());
            output.writeLong(record.enqueuedAtEpochMillis());
            output.writeLong(record.availableAtEpochMillis());
            output.writeLong(record.leaseUntilEpochMillis());
            output.writeLong(record.leaseToken());
            output.writeInt(record.attempts());
            writeString(output, record.codecId());
            writeString(output, record.payloadSummary());
            writeString(output, record.payloadType());
            writeString(output, record.payloadVersion());
            writeString(output, record.idempotencyKey());
            writeString(output, record.lastError());
            writeString(output, record.lastErrorType());
            writeString(output, record.errorCategory());
            output.writeInt(record.interruptions());
            output.writeInt(record.payload().length);
            output.write(record.payload());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode async queue record", exception);
        }
    }

    QueueRecord decode(byte[] bytes) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = input.readInt();
            if (magic != MAGIC) {
                throw new IllegalStateException("Async queue record has invalid magic");
            }
            short version = input.readShort();
            if (version != FORMAT_VERSION && version != LEGACY_FORMAT_VERSION) {
                throw new IllegalStateException("Unsupported async queue record format version: " + version);
            }
            long id = input.readLong();
            int laneId = input.readInt();
            int stateOrdinal = input.readUnsignedByte();
            AsyncInvocationState[] states = AsyncInvocationState.values();
            if (stateOrdinal >= states.length) {
                throw new IllegalStateException("Async queue record contains invalid state: " + stateOrdinal);
            }
            long enqueuedAt = input.readLong();
            long availableAt = input.readLong();
            long leaseUntil = input.readLong();
            long leaseToken = input.readLong();
            int attempts = input.readInt();
            if (attempts < 0) {
                throw new IllegalStateException("Async queue record contains negative attempts");
            }
            String codecId = readString(input);
            String summary = readString(input);
            String payloadType = readString(input);
            String payloadVersion = readString(input);
            String idempotencyKey = readString(input);
            String lastError = readString(input);
            String lastErrorType = readString(input);
            String errorCategory = readString(input);
            int interruptions = version >= FORMAT_VERSION ? input.readInt() : 0;
            if (interruptions < 0) {
                throw new IllegalStateException("Async queue record contains negative interruptions");
            }
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength > maxPayloadBytes) {
                throw new IllegalStateException("Async queue record payload length is invalid: " + payloadLength);
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength) {
                throw new EOFException("Async queue record payload is truncated");
            }
            if (input.available() != 0) {
                throw new IllegalStateException("Async queue record contains trailing bytes");
            }
            return new QueueRecord(
                    id,
                    laneId,
                    codecId,
                    payload,
                    summary,
                    payloadType,
                    payloadVersion,
                    idempotencyKey,
                    states[stateOrdinal],
                    enqueuedAt,
                    availableAt,
                    leaseUntil,
                    leaseToken,
                    attempts,
                    lastError,
                    lastErrorType,
                    errorCategory,
                    interruptions
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Could not decode async queue record", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_METADATA_BYTES) {
            throw new IllegalArgumentException("Async queue metadata exceeds " + MAX_METADATA_BYTES + " UTF-8 bytes");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length == -1) {
            return null;
        }
        if (length < 0 || length > MAX_METADATA_BYTES) {
            throw new IllegalStateException("Async queue metadata length is invalid: " + length);
        }
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new EOFException("Async queue metadata is truncated");
        }
        return new String(encoded, StandardCharsets.UTF_8);
    }
}
