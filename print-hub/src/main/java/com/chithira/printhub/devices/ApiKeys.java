package com.chithira.printhub.devices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * API keys look like {@code chp_<prefix 8>_<secret 32>}. The prefix finds the device;
 * only the SHA-256 of the whole key is stored, so a leaked database reveals no key.
 */
public final class ApiKeys {

    public record NewKey(String plain, String prefix, String hash) {}

    private static final String CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {}

    public static NewKey generate() {
        String prefix = random(8), secret = random(32);
        String plain = "chp_" + prefix + "_" + secret;
        return new NewKey(plain, prefix, hash(plain));
    }

    /** The prefix of a well-formed key, or null. */
    public static String prefixOf(String key) {
        if (key == null) return null;
        String[] parts = key.split("_");
        if (parts.length != 3 || !parts[0].equals("chp") || parts[1].length() != 8 || parts[2].length() != 32) return null;
        return parts[1];
    }

    public static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison of a presented key against the stored hash. */
    public static boolean matches(String key, String storedHash) {
        return MessageDigest.isEqual(hash(key).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private static String random(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) builder.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        return builder.toString();
    }
}
