package com.kindlerss.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAccessTest {

    @Test
    void matchesConfiguredEmailsCaseInsensitively() {
        AdminAccess access = new AdminAccess("owner@example.com, second@example.com");

        assertTrue(access.isAdmin("OWNER@example.com"));
        assertTrue(access.isAdmin("second@example.com"));
        assertFalse(access.isAdmin("user@example.com"));
    }
}
