package com.kindlerss.web;

import jakarta.servlet.http.HttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a request body into memory with a hard size cap, for the two endpoints
 * strangers can POST to without logging in (payment webhook, newsletter inbox).
 * A declared Content-Length over the cap is refused before a byte is read; a body
 * without one (chunked) is read only up to the cap.
 */
final class BoundedBody {

    /** The body was larger than allowed. Maps to 413. */
    static final class TooLargeException extends Exception {
        TooLargeException(long limit) {
            super("Request body exceeds " + limit + " bytes");
        }
    }

    private BoundedBody() {
    }

    static byte[] read(HttpServletRequest request, int maxBytes) throws IOException, TooLargeException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            throw new TooLargeException(maxBytes);
        }
        InputStream in = request.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream(declared > 0 ? (int) declared : 8192);
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new TooLargeException(maxBytes);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
