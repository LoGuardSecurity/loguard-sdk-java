package com.loguard.sdk.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Request signing for the LoGuard Java SDK.
 *
 * Byte-for-byte the same protocol as the Python/Node/Go/C#/PHP SDKs:
 * HMAC-SHA256 over {@code "{unix_timestamp}." + bodyBytes}, keyed
 * with the raw API key. The server verifies:
 *
 * <ol>
 *   <li>{@code X-LoGuard-Timestamp} is fresh (&lt; 5 minutes old) — replay protection.</li>
 *   <li>{@code X-LoGuard-Signature} matches HMAC-SHA256(apiKey, "{ts}." + body).</li>
 * </ol>
 *
 * Only what is actually sent is ever signed: build the body first,
 * sign those exact bytes, send those exact bytes.
 */
public final class Signing {

    public static final String SDK_NAME = "loguard-java-sdk";
    public static final String SDK_VERSION = "1.0.0";
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Signing() {
    }

    public static final class Signed {
        public final byte[] body;
        public final Map<String, String> headers;

        Signed(byte[] body, Map<String, String> headers) {
            this.body = body;
            this.headers = headers;
        }
    }

    public static byte[] buildBody(Object payload) {
        return Json.write(payload).getBytes(StandardCharsets.UTF_8);
    }

    public static Signed sign(String apiKey, byte[] bodyBytes) {
        long timestamp = System.currentTimeMillis() / 1000L;
        String tsStr = Long.toString(timestamp);

        byte[] prefix = (tsStr + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signedPayload = new byte[prefix.length + bodyBytes.length];
        System.arraycopy(prefix, 0, signedPayload, 0, prefix.length);
        System.arraycopy(bodyBytes, 0, signedPayload, prefix.length, bodyBytes.length);

        String signature = hmacSha256Hex(apiKey, signedPayload);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Api-Key", apiKey);
        headers.put("X-LoGuard-Timestamp", tsStr);
        headers.put("X-LoGuard-Signature", "sha256=" + signature);
        headers.put("X-Request-ID", randomUuidV4());
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", SDK_NAME + "/" + SDK_VERSION);

        return new Signed(bodyBytes, headers);
    }

    private static String hmacSha256Hex(String key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(data);
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is a JCE-mandatory algorithm on every conforming
            // JVM; a JVM that lacks it cannot run this SDK safely at all.
            throw new IllegalStateException("HmacSHA256 unavailable on this JVM", e);
        }
    }

    private static String randomUuidV4() {
        return UUID.randomUUID().toString();
    }
}
