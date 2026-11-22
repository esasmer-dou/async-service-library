package com.reactor.asl.admin.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslAdminSignerTest {
    private static final String SECRET = "0123456789abcdef-shared";
    private final AslAdminSigner signer = new AslAdminSigner(SECRET);
    private final byte[] body = "payload".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsValidSignatureWithinWindow() {
        String header = signer.sign(body, 1_000);
        assertTrue(signer.verify(header, body, 1_000));
        assertTrue(signer.verify(header, body, 1_060));
        assertTrue(new AslAdminSigner(SECRET).verify(header, body, 940));
    }

    @Test
    void rejectsTamperingWrongSecretExpiredAndMalformed() {
        String header = signer.sign(body, 1_000);
        assertFalse(signer.verify(header, "payloaD".getBytes(StandardCharsets.UTF_8), 1_000));
        assertFalse(new AslAdminSigner("another-secret-0123456").verify(header, body, 1_000));
        assertFalse(signer.verify(header, body, 1_061));
        assertFalse(signer.verify(null, body, 1_000));
        assertFalse(signer.verify("t=abc,s=xyz", body, 1_000));
        assertFalse(signer.verify("t=1000", body, 1_000));
        assertFalse(signer.verify("t=1000,s=***", body, 1_000));
        assertFalse(signer.verify("x".repeat(300), body, 1_000));
        assertTrue(signer.verify(signer.sign(null, 5), null, 5));
    }

    @Test
    void rejectsShortSecret() {
        assertThrows(IllegalArgumentException.class, () -> new AslAdminSigner("short"));
        assertThrows(IllegalArgumentException.class, () -> new AslAdminSigner(null));
        assertThrows(IllegalArgumentException.class, () -> new AslAdminSigner(SECRET, 0));
    }
}
