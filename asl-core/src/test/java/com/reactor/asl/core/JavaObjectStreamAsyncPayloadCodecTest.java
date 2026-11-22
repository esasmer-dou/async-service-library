package com.reactor.asl.core;

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaObjectStreamAsyncPayloadCodecTest {
    @Test
    void rejectsApplicationTypesUnlessExplicitlyAllowlisted() {
        JavaObjectStreamAsyncPayloadCodec codec = new JavaObjectStreamAsyncPayloadCodec();

        assertThrows(
                AsyncPayloadCodecException.class,
                () -> codec.encode("svc", "publish(payload)", new Object[]{new Payload("secret")})
        );
    }

    @Test
    void roundTripsExplicitlyAllowlistedApplicationType() {
        JavaObjectStreamAsyncPayloadCodec codec = new JavaObjectStreamAsyncPayloadCodec(
                Set.of(Payload.class.getName()),
                Set.of(),
                16,
                100,
                100,
                4_096
        );

        byte[] encoded = codec.encode("svc", "publish(payload)", new Object[]{new Payload("value")});
        Object[] decoded = codec.decode("svc", "publish(payload)", encoded);

        assertEquals(new Payload("value"), decoded[0]);
    }

    private record Payload(String value) implements Serializable {
    }
}
