package com.findlocally.api.service;

import com.findlocally.api.TestMedia;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class MediaInspectorTests {
    private final MediaInspector inspector=new MediaInspector("missing-test-ffprobe","missing-test-ffmpeg",600,8192);
    @Test void validatesImageBytesAndDimensionsWithoutTrustingTheMimeHeader() {
        var valid=inspector.inspect(new MockMultipartFile("file","photo.jpg","image/jpeg",TestMedia.jpeg()),false);
        assertEquals("READY",valid.state());assertEquals(2,valid.width());assertEquals(3,valid.height());
        assertEquals("FAILED",inspector.inspect(new MockMultipartFile("file","fake.jpg","image/jpeg",new byte[50]),false).state());
    }
    @Test void missingVideoToolsAndSpoofedVideoFailClosed() {
        assertEquals("FAILED",inspector.inspect(new MockMultipartFile("file","bad.mp4","video/mp4",new byte[50]),false).state());
        assertEquals("FAILED",inspector.inspect(new MockMultipartFile("file","video.mp4","video/mp4","0000ftyp00000000".getBytes()),false).state());
    }
    @Test void dimensionLimitCannotBeBypassedByFileSize() {
        var limited=new MediaInspector("missing","missing",600,1);
        assertEquals("FAILED",limited.inspect(new MockMultipartFile("file","photo.jpg","image/jpeg",TestMedia.jpeg()),false).state());
    }
}
