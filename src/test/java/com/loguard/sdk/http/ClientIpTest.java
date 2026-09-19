package com.loguard.sdk.http;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientIpTest {

    @Test
    void untrustedPeerIgnoresForwardedFor() {
        String ip = ClientIp.resolve("203.0.113.9", "9.9.9.9", List.of());
        assertEquals("203.0.113.9", ip);
    }

    @Test
    void trustedProxyForwardsFirstXffEntry() {
        String ip = ClientIp.resolve("10.0.0.1", "198.51.100.7, 10.0.0.1", List.of("10.0.0.1"));
        assertEquals("198.51.100.7", ip);
    }

    @Test
    void trustedProxyWithMalformedXffFallsBackToPeer() {
        String ip = ClientIp.resolve("10.0.0.1", "not-an-ip", List.of("10.0.0.1"));
        assertEquals("10.0.0.1", ip);
    }

    @Test
    void cidrTrustedProxyRange() {
        String ip = ClientIp.resolve("10.0.5.42", "198.51.100.7", List.of("10.0.0.0/16"));
        assertEquals("198.51.100.7", ip);
    }

    @Test
    void cidrDoesNotMatchOutsideRange() {
        String ip = ClientIp.resolve("10.1.5.42", "198.51.100.7", List.of("10.0.0.0/16"));
        assertEquals("10.1.5.42", ip);
    }

    @Test
    void emptyPeerFallsBackToLoopback() {
        String ip = ClientIp.resolve("", null, List.of());
        assertEquals("127.0.0.1", ip);
    }

    @Test
    void spoofAttemptFromUntrustedDirectPeerIsIgnored() {
        String ip = ClientIp.resolve("198.51.100.66", "1.2.3.4", List.of("10.0.0.1"));
        assertEquals("198.51.100.66", ip);
    }
}
