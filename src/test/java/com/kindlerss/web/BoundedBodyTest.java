package com.kindlerss.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedBodyTest {

    /** A chunked request declares no length; the cap has to hold while reading. */
    @Test
    void aBodyWithoutADeclaredLengthIsCutOffAtTheCap() {
        MockHttpServletRequest request = undeclared(new byte[5000]);

        assertThrows(BoundedBody.TooLargeException.class, () -> BoundedBody.read(request, 4096));
    }

    @Test
    void aDeclaredLengthOverTheCapIsRefusedUpFront() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x") {
            @Override
            public long getContentLengthLong() {
                return 10_000_000L;
            }
        };
        request.setContent(new byte[10]);

        assertThrows(BoundedBody.TooLargeException.class, () -> BoundedBody.read(request, 4096));
    }

    @Test
    void aBodyWithinTheCapIsReadWhole() throws Exception {
        byte[] body = "{\"ok\":true}".getBytes();

        assertArrayEquals(body, BoundedBody.read(undeclared(body), 4096));
        assertArrayEquals(new byte[4096], BoundedBody.read(undeclared(new byte[4096]), 4096));
    }

    private static MockHttpServletRequest undeclared(byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(content);
        return request;
    }
}
