package com.reactor.asl.admin.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Signs and verifies message bodies with HMAC-SHA256 over {@code epochSeconds + "." + body}.
 * Header format: {@code t=<epochSeconds>,s=<base64url signature>}. Thread-safe.
 */
public final class AslAdminSigner {
    /** Minimum secret length in UTF-8 bytes. */
    public static final int MIN_SECRET_BYTES = 16;
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final long toleranceSeconds;

    public AslAdminSigner(String secret) {
        this(secret, 60);
    }

    public AslAdminSigner(String secret, long toleranceSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("Shared secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (toleranceSeconds <= 0) {
            throw new IllegalArgumentException("toleranceSeconds must be positive");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.toleranceSeconds = toleranceSeconds;
        mac();
    }

    public String sign(byte[] body, long epochSeconds) {
        return "t=" + epochSeconds + ",s=" + ENCODER.encodeToString(signature(body, epochSeconds));
    }

    public boolean verify(String header, byte[] body, long nowEpochSeconds) {
        if (header == null || header.length() > 256) {
            return false;
        }
        long timestamp = -1;
        String signature = null;
        for (String part : header.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("t=")) {
                try {
                    timestamp = Long.parseLong(trimmed.substring(2));
                } catch (NumberFormatException exception) {
                    return false;
                }
            } else if (trimmed.startsWith("s=")) {
                signature = trimmed.substring(2);
            }
        }
        if (timestamp < 0 || signature == null || Math.abs(nowEpochSeconds - timestamp) > toleranceSeconds) {
            return false;
        }
        byte[] provided;
        try {
            provided = DECODER.decode(signature);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(provided, signature(body, timestamp));
    }

    private byte[] signature(byte[] body, long epochSeconds) {
        Mac mac = mac();
        mac.update(Long.toString(epochSeconds).getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) '.');
        if (body != null) {
            mac.update(body);
        }
        return mac.doFinal();
    }

    private Mac mac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is not available", exception);
        }
    }
}
