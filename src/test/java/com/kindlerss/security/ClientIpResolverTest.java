package com.kindlerss.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    private static final String RAILWAY_EDGE = "100.64.0.2";
    private static final String CLOUDFLARE = "172.68.10.20";
    private static final String CLOUDFLARE_V6 = "2a06:98c0:3600::103";
    private static final String CLIENT = "203.0.113.7";

    private final ClientIpResolver resolver = ClientIpResolver.fromSettings(null, true);

    private String resolve(String peer, String... forwardedFor) {
        return resolver.resolve(peer, List.of(forwardedFor));
    }

    @Test
    void cloudflareThenRailwayResolvesTheRealClient() {
        // Client -> Cloudflare (appends client) -> Railway edge (appends Cloudflare).
        assertThat(resolve(RAILWAY_EDGE, CLIENT + ", " + CLOUDFLARE)).isEqualTo(CLIENT);
    }

    @Test
    void aSpoofedLeftmostValueIsIgnored() {
        assertThat(resolve(RAILWAY_EDGE, "198.51.100.66, " + CLIENT + ", " + CLOUDFLARE))
                .isEqualTo(CLIENT);
        // A forged trusted-looking entry on the left changes nothing either.
        assertThat(resolve(RAILWAY_EDGE, "10.0.0.1, " + CLIENT + ", " + CLOUDFLARE))
                .isEqualTo(CLIENT);
    }

    @Test
    void aRequestStraightToTheOriginResolvesToTheCallerRailwaySaw() {
        // Bypasses Cloudflare via *.up.railway.app. The caller forged a leftmost
        // value (and would forge CF-Connecting-IP too, which is never read); Railway's
        // edge appended the real caller.
        assertThat(resolve(RAILWAY_EDGE, "198.51.100.66, " + CLIENT)).isEqualTo(CLIENT);
        // Forging a Cloudflare address does not help: it is left of the real caller.
        assertThat(resolve(RAILWAY_EDGE, CLOUDFLARE + ", " + CLIENT)).isEqualTo(CLIENT);
    }

    @Test
    void withoutAProxyTheHeaderIsIgnored() {
        assertThat(resolve(CLIENT)).isEqualTo(CLIENT);
        assertThat(resolve(CLIENT, "198.51.100.66")).isEqualTo(CLIENT);
    }

    @Test
    void anUntrustedPeerCannotForgeAChain() {
        assertThat(resolve("198.51.100.9", "203.0.113.50, " + CLOUDFLARE)).isEqualTo("198.51.100.9");
    }

    @Test
    void aTrustedPeerWithoutAHeaderIsItself() {
        assertThat(resolve("172.18.0.5")).isEqualTo("172.18.0.5");
    }

    @Test
    void caddyOnTheDockerNetworkResolvesTheClient() {
        // VPS: client -> Caddy (172.18.0.2, appends client) -> app.
        assertThat(resolve("172.18.0.2", "198.51.100.66, " + CLIENT)).isEqualTo(CLIENT);
    }

    @Test
    void allTrustedFallsBackToTheLeftmost() {
        assertThat(resolve("127.0.0.1", "10.1.2.3, 192.168.1.1")).isEqualTo("10.1.2.3");
    }

    @Test
    void garbageStopsTheWalkAtTheNearestTrustedHop() {
        assertThat(resolve(RAILWAY_EDGE, "evil.example, " + CLOUDFLARE)).isEqualTo(CLOUDFLARE);
        assertThat(resolve(RAILWAY_EDGE, "unknown")).isEqualTo(RAILWAY_EDGE);
        assertThat(resolve(RAILWAY_EDGE, "999.1.1.1")).isEqualTo(RAILWAY_EDGE);
    }

    @Test
    void severalHeaderLinesAreOneChain() {
        assertThat(resolver.resolve(RAILWAY_EDGE, List.of("198.51.100.66", CLIENT, CLOUDFLARE)))
                .isEqualTo(CLIENT);
    }

    @Test
    void ipv6ClientsAndProxiesResolve() {
        String client = "2001:db8::1234";
        assertThat(resolve("fd12::1", client + ", " + CLOUDFLARE_V6)).isEqualTo("2001:db8:0:0:0:0:0:1234");
        assertThat(resolve("::1", "[2001:db8::99]:4711, " + CLIENT + ", " + CLOUDFLARE_V6))
                .isEqualTo(CLIENT);
        assertThat(resolve("2001:db8::5", "198.51.100.66")).isEqualTo("2001:db8:0:0:0:0:0:5");
        assertThat(resolve("fe80::1%eth0", client)).isEqualTo("2001:db8:0:0:0:0:0:1234");
    }

    @Test
    void cloudflareCanBeLeftOutOfTheDefaults() {
        ClientIpResolver privateOnly = ClientIpResolver.fromSettings("", false);
        assertThat(privateOnly.resolve(RAILWAY_EDGE, List.of(CLIENT + ", " + CLOUDFLARE)))
                .isEqualTo(CLOUDFLARE);
    }

    @Test
    void anExplicitListReplacesThePrivateDefaults() {
        ClientIpResolver custom = ClientIpResolver.fromSettings("192.0.2.0/24, 2001:db8:ff::/48", false);
        assertThat(custom.resolve("192.0.2.10", List.of("198.51.100.66, " + CLIENT))).isEqualTo(CLIENT);
        assertThat(custom.resolve("10.0.0.1", List.of(CLIENT))).isEqualTo("10.0.0.1");
        assertThat(custom.resolve("2001:db8:ff::1", List.of(CLIENT))).isEqualTo(CLIENT);
    }

    @Test
    void aMalformedRangeFailsLoudly() {
        assertThatThrownBy(() -> ClientIpResolver.fromSettings("10.0.0.0/33", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientIpResolver.fromSettings("railway.internal", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cidrBoundariesAreExact() {
        ClientIpResolver r = ClientIpResolver.fromSettings("100.64.0.0/10", false);
        assertThat(r.isTrusted(ClientIpResolver.parse("100.127.255.255"))).isTrue();
        assertThat(r.isTrusted(ClientIpResolver.parse("100.128.0.0"))).isFalse();
        assertThat(r.isTrusted(ClientIpResolver.parse("100.63.255.255"))).isFalse();
    }
}
