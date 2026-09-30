package io.github.badfisher.ailog.domain.text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared SHA-256 mechanics. Each caller retains its own canonical input format.
 */
public final class Sha256 {

    private static final String ALGORITHM = "SHA-256";
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private Sha256() {
    }

    /** Hash UTF-8 text without trimming, normalizing or changing field boundaries. */
    public static String sha256(String value) {
        return toHex(newDigest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** Return a fresh digest for callers with an existing incremental binary format. */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Encode digest bytes as lowercase hexadecimal, preserving leading zeros. */
    public static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            result.append(HEX_DIGITS[unsigned >>> 4]);
            result.append(HEX_DIGITS[unsigned & 0x0f]);
        }
        return result.toString();
    }
}
