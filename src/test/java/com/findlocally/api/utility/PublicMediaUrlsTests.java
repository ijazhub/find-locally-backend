package com.findlocally.api.utility;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class PublicMediaUrlsTests {
    @Test
    void requiresConfigurationAndRejectsEscapingThePublicPrefix() {
        assertThrows(ResponseStatusException.class, () -> new PublicMediaUrls("").url("avatar.png"));
        var urls = new PublicMediaUrls("https://media.example.com/public/");
        assertEquals("https://media.example.com/public/my%20photo.png", urls.url("my photo.png"));
        assertThrows(ResponseStatusException.class, () -> urls.url("../private/document.png"));
        assertThrows(ResponseStatusException.class, () -> urls.url("//other.example.com/image.png"));
    }
}
