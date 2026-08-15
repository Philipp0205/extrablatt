package com.kindlerss.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeHttpClientTest {

    @Test
    void blocksPrivateAndLoopbackAddresses() throws Exception {
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("10.0.0.1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("192.168.1.1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("172.16.5.5")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("169.254.1.1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("100.64.1.2")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("224.0.0.1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("::1")));
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("fc00::1")));
    }

    @Test
    void allowsPublicAddresses() throws Exception {
        assertFalse(SafeHttpClient.isBlockedAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(SafeHttpClient.isBlockedAddress(InetAddress.getByName("1.1.1.1")));
    }

    @Test
    void rejectsNonHttpSchemesWithoutDns() {
        com.kindlerss.config.AppProperties props = new com.kindlerss.config.AppProperties(
                "from@example.com", null, "remember",
                new com.kindlerss.config.AppProperties.Http(null, null, 1024), null, null, null, null
        );
        SafeHttpClient client = new SafeHttpClient(props);
        assertThrows(SafeHttpClient.FetchException.class, () -> client.validateAndResolve("file:///etc/passwd"));
        assertThrows(SafeHttpClient.FetchException.class, () -> client.validateAndResolve("ftp://example.com/a"));
        assertThrows(SafeHttpClient.FetchException.class, () -> client.validateAndResolve("javascript:alert(1)"));
    }
}
