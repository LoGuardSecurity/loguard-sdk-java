package com.loguard.sdk;

import com.loguard.sdk.internal.Signing;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SigningTest {

    @Test
    void signatureIsHmacSha256OfTimestampDotBody() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("events", java.util.List.of());
        byte[] body = Signing.buildBody(payload);

        Signing.Signed signed = Signing.sign("super-secret-key", body);
        assertArrayEquals(body, signed.body, "must sign exactly the bytes that get sent");

        String timestamp = signed.headers.get("X-LoGuard-Timestamp");
        String signedPayload = timestamp + "." ;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("super-secret-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] prefix = signedPayload.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(body, 0, combined, prefix.length, body.length);
        byte[] expectedRaw = mac.doFinal(combined);
        StringBuilder hex = new StringBuilder();
        for (byte b : expectedRaw) {
            hex.append(String.format("%02x", b));
        }

        assertEquals("sha256=" + hex, signed.headers.get("X-LoGuard-Signature"));
    }

    @Test
    void headersIncludeRequiredFields() {
        Signing.Signed signed = Signing.sign("k", Signing.buildBody(Map.of("a", 1)));
        assertTrue(signed.headers.containsKey("X-Api-Key"));
        assertTrue(signed.headers.containsKey("X-LoGuard-Timestamp"));
        assertTrue(signed.headers.containsKey("X-LoGuard-Signature"));
        assertTrue(signed.headers.containsKey("X-Request-ID"));
        assertEquals("application/json", signed.headers.get("Content-Type"));
        assertTrue(signed.headers.get("User-Agent").startsWith("loguard-java-sdk/"));
    }

    @Test
    void eachSignatureUsesAFreshRequestId() {
        byte[] body = Signing.buildBody(Map.of("a", 1));
        Signing.Signed s1 = Signing.sign("k", body);
        Signing.Signed s2 = Signing.sign("k", body);
        assertNotEquals(s1.headers.get("X-Request-ID"), s2.headers.get("X-Request-ID"));
    }
}
