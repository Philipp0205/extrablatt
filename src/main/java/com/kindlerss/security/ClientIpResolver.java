package com.kindlerss.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Works out which address a request really came from when it may have passed
 * through proxies that append to {@code X-Forwarded-For}.
 *
 * <p>Production is client, Cloudflare, Railway's edge, app. Each proxy appends the
 * address it saw, so the header reads {@code [whatever the client sent], client,
 * cloudflare} and only the right-hand end is trustworthy. The leftmost value is
 * whatever the client chose to send. So:
 *
 * <ul>
 *   <li>If the socket peer is not a trusted proxy, the header is ignored and the
 *       peer is the client. Nobody but our own proxies gets to speak for someone
 *       else.</li>
 *   <li>Otherwise the header is walked from the right, skipping trusted proxies
 *       (Railway's private hops, then Cloudflare). The first address that is not a
 *       trusted proxy is the client.</li>
 *   <li>If every entry is trusted, the leftmost is used.</li>
 * </ul>
 *
 * <p>{@code CF-Connecting-IP} is deliberately never read. The Railway origin is
 * reachable without going through Cloudflare, and a Cloudflare Worker can set the
 * header too, so its presence proves nothing. Walking the appended chain gives the
 * right answer either way: a request straight to the origin resolves to the
 * address Railway's edge appended.
 *
 * <p>Entries that are not IP literals (hostnames, {@code unknown}, garbage) stop
 * the walk; the nearest trusted hop to their right is used instead, so nothing a
 * client typed can become the answer. No entry is ever resolved through DNS.
 */
public final class ClientIpResolver {

    public static final String FORWARDED_FOR = "X-Forwarded-For";

    /** Loopback, RFC 1918, CGNAT / Railway private network, IPv6 ULA and link-local. */
    public static final List<String> PRIVATE_RANGES = List.of(
            "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "100.64.0.0/10", "169.254.0.0/16",
            "::1/128", "fc00::/7", "fe80::/10");

    /**
     * Cloudflare's published edge ranges, copied from https://www.cloudflare.com/ips-v4
     * and https://www.cloudflare.com/ips-v6 on 2026-11-07. Cloudflare changes these
     * rarely; refresh this list when it does.
     */
    public static final List<String> CLOUDFLARE_RANGES = List.of(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
            "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32");

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

    private final List<Cidr> trusted;

    public ClientIpResolver(List<String> trustedRanges) {
        List<Cidr> ranges = new ArrayList<>();
        for (String range : trustedRanges) {
            if (range != null && !range.isBlank()) {
                ranges.add(Cidr.parse(range.trim()));
            }
        }
        this.trusted = Collections.unmodifiableList(ranges);
    }

    /**
     * Builds the resolver from the {@code app.trusted-proxies} setting.
     *
     * @param configured comma-separated CIDRs; blank means {@link #PRIVATE_RANGES}
     * @param trustCloudflare whether {@link #CLOUDFLARE_RANGES} are trusted as well
     */
    public static ClientIpResolver fromSettings(String configured, boolean trustCloudflare) {
        List<String> ranges = new ArrayList<>();
        if (configured == null || configured.isBlank()) {
            ranges.addAll(PRIVATE_RANGES);
        } else {
            for (String part : configured.split(",")) {
                if (!part.isBlank()) {
                    ranges.add(part.trim());
                }
            }
        }
        if (trustCloudflare) {
            ranges.addAll(CLOUDFLARE_RANGES);
        }
        return new ClientIpResolver(ranges);
    }

    /**
     * @param peer the socket's remote address, as the container saw it
     * @param forwardedFor every {@code X-Forwarded-For} header value, in order
     * @return the client address in canonical form
     */
    public String resolve(String peer, List<String> forwardedFor) {
        InetAddress peerAddress = parse(peer);
        if (peerAddress == null) {
            return peer;
        }
        if (!isTrusted(peerAddress) || forwardedFor == null || forwardedFor.isEmpty()) {
            return peerAddress.getHostAddress();
        }
        List<String> hops = new ArrayList<>();
        for (String value : forwardedFor) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                String hop = part.trim();
                if (!hop.isEmpty()) {
                    hops.add(hop);
                }
            }
        }
        InetAddress nearest = peerAddress;
        for (int i = hops.size() - 1; i >= 0; i--) {
            InetAddress hop = parse(hops.get(i));
            if (hop == null) {
                return nearest.getHostAddress();
            }
            if (!isTrusted(hop)) {
                return hop.getHostAddress();
            }
            nearest = hop;
        }
        return nearest.getHostAddress();
    }

    boolean isTrusted(InetAddress address) {
        for (Cidr cidr : trusted) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** Parses an IP literal, tolerating brackets, ports and IPv6 zone ids. Never does DNS. */
    static InetAddress parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return null;
            }
            value = value.substring(1, close);
        } else if (value.indexOf(':') >= 0 && value.indexOf(':') == value.lastIndexOf(':')
                && value.indexOf('.') >= 0) {
            value = value.substring(0, value.indexOf(':'));
        }
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        try {
            if (IPV4.matcher(value).matches()) {
                String[] octets = value.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(octets[i]);
                    if (octet > 255) {
                        return null;
                    }
                    bytes[i] = (byte) octet;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (value.indexOf(':') < 0 || !IPV6.matcher(value).matches()) {
                return null;
            }
            // Contains a colon, so the JDK parses it as an IPv6 literal and never
            // falls back to a name lookup.
            return InetAddress.getByName(value);
        } catch (UnknownHostException | IllegalArgumentException e) {
            return null;
        }
    }

    record Cidr(byte[] network, int prefix) {
        static Cidr parse(String text) {
            int slash = text.indexOf('/');
            String host = slash < 0 ? text : text.substring(0, slash);
            InetAddress address = ClientIpResolver.parse(host);
            if (address == null) {
                throw new IllegalArgumentException("Not a CIDR range: " + text);
            }
            int bits = address.getAddress().length * 8;
            int prefix = slash < 0 ? bits : Integer.parseInt(text.substring(slash + 1).trim());
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Bad prefix length in " + text);
            }
            return new Cidr(address.getAddress(), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (candidate[full] & mask) == (network[full] & mask);
        }
    }
}
