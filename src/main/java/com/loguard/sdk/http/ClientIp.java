package com.loguard.sdk.http;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Trusted-proxy-aware real-client-IP resolution.
 *
 * Port of the same logic used across every LoGuard SDK's HTTP
 * middleware (Python's loguard/_ip_utils.py, the PHP SDK's
 * LoGuard\Sdk\Http\ClientIp, etc).
 *
 * <p>{@code X-Forwarded-For} is just a header — whoever makes the
 * request controls what's in it. It's only meaningful once we know
 * the request actually passed through a proxy we operate, which is
 * what {@code trustedProxies} identifies; otherwise a caller could
 * claim to be any IP and walk past whatever IP-based checks the
 * caller of this method relies on (rate limits, per-IP alerting,
 * blocklists).
 *
 * <p>No trusted proxies configured means the header is never
 * consulted — that's the resting state, not a special mode.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String resolve(String directPeerIp, String xForwardedFor, List<String> trustedProxies) {
        String peer = (directPeerIp == null || directPeerIp.isEmpty()) ? "127.0.0.1" : directPeerIp;

        if (xForwardedFor == null || xForwardedFor.isEmpty() || trustedProxies == null || trustedProxies.isEmpty()) {
            return peer;
        }

        if (!matchesAny(peer, trustedProxies)) {
            return peer;
        }

        String candidate = xForwardedFor.split(",", 2)[0].trim();
        if (isValidIp(candidate)) {
            return candidate;
        }

        return peer;
    }

    private static boolean isValidIp(String ip) {
        try {
            InetAddress.getByName(ip);
            // getByName will resolve hostnames too; restrict to literal
            // IPs only by requiring the textual form to round-trip.
            return looksLikeLiteralIp(ip);
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean looksLikeLiteralIp(String ip) {
        // IPv4 or IPv6 literal only -- never a hostname (which
        // InetAddress.getByName would otherwise happily resolve,
        // potentially triggering an unwanted DNS lookup driven by
        // attacker-controlled header content).
        return ip.matches("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$") || ip.contains(":");
    }

    private static boolean matchesAny(String ip, List<String> trusted) {
        for (String entry : trusted) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            if (entry.contains("/")) {
                if (cidrMatch(ip, entry)) {
                    return true;
                }
            } else if (entry.equals(ip)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cidrMatch(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/", 2);
            InetAddress subnet = InetAddress.getByName(parts[0]);
            InetAddress addr = InetAddress.getByName(ip);
            if (subnet.getAddress().length != addr.getAddress().length) {
                return false;
            }
            int prefixLen = Integer.parseInt(parts[1]);

            BigInteger ipInt = new BigInteger(1, addr.getAddress());
            BigInteger subnetInt = new BigInteger(1, subnet.getAddress());
            int totalBits = addr.getAddress().length * 8;
            BigInteger mask = BigInteger.ZERO.setBit(totalBits).subtract(BigInteger.ONE)
                .shiftRight(totalBits - prefixLen).shiftLeft(totalBits - prefixLen);

            return ipInt.and(mask).equals(subnetInt.and(mask));
        } catch (Exception e) {
            return false;
        }
    }
}
