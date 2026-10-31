package com.findlocally.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

public final class TestMedia {
    private TestMedia() {}
    public static byte[] jpeg() {
        try {
            var output=new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"jpeg",output);
            return output.toByteArray();
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
}
