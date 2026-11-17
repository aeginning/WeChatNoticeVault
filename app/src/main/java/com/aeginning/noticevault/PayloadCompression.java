package com.aeginning.noticevault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Small bounded GZIP helper for encrypted record payloads. */
public final class PayloadCompression {
    private static final int BUFFER_SIZE = 4096;

    private PayloadCompression() { }

    public static byte[] compress(byte[] plain) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (FastGzipOutputStream gzip = new FastGzipOutputStream(output)) {
            gzip.write(plain);
        }
        return output.toByteArray();
    }

    public static byte[] decompress(byte[] compressed, int maxOutputBytes) throws IOException {
        if (compressed == null || maxOutputBytes < 1) {
            throw new IllegalArgumentException("Invalid compressed payload limits");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(maxOutputBytes, Math.max(BUFFER_SIZE, compressed.length * 2)));
        try (InputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int total = 0;
            int length;
            while ((length = gzip.read(buffer)) != -1) {
                total += length;
                if (total > maxOutputBytes) throw new IOException("Decompressed record exceeds size limit");
                output.write(buffer, 0, length);
            }
        }
        return output.toByteArray();
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        FastGzipOutputStream(ByteArrayOutputStream output) throws IOException {
            super(output, BUFFER_SIZE);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }
}
