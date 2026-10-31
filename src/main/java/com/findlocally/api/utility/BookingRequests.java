package com.findlocally.api.utility;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import tools.jackson.databind.ObjectMapper;

public final class BookingRequests {
    private BookingRequests() {}
    public static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(new ObjectMapper().writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
