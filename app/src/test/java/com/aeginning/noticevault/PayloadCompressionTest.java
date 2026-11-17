package com.aeginning.noticevault;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PayloadCompressionTest {
    @Test
    public void compressesAndRestoresRepeatedMessageText() throws Exception {
        String sample = "这是一条重复内容较多的通知消息。".repeat(300);
        byte[] plain = sample.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = PayloadCompression.compress(plain);

        assertTrue("repeated notification payload should compress", compressed.length < plain.length);
        assertArrayEquals(plain, PayloadCompression.decompress(compressed, plain.length + 1));
    }

    @Test
    public void enforcesDecompressionLimit() throws Exception {
        byte[] plain = "A".repeat(16_384).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = PayloadCompression.compress(plain);
        try {
            PayloadCompression.decompress(compressed, 1024);
            fail("oversized decompressed payload must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("size limit"));
        }
    }

    @Test
    public void retainsNonCompressiblePayloadBytes() throws Exception {
        byte[] plain = new byte[4096];
        new java.security.SecureRandom().nextBytes(plain);
        byte[] compressed = PayloadCompression.compress(plain);
        // Compression helper is lossless even when the caller decides not to use its output.
        assertArrayEquals(plain, PayloadCompression.decompress(compressed, 8192));
    }
}
