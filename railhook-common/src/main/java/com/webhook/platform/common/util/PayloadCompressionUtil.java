package com.webhook.platform.common.util;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Gzip then Base64, so the result fits a text column. */
@Slf4j
public class PayloadCompressionUtil {

    private static final int DEFAULT_COMPRESSION_THRESHOLD_BYTES = 1024;

    public static CompressionResult compress(String payload, int thresholdBytes) {
        if (payload == null || payload.isEmpty()) {
            return new CompressionResult(payload, false, 0, 0);
        }

        byte[] uncompressedBytes = payload.getBytes(StandardCharsets.UTF_8);
        int originalSize = uncompressedBytes.length;

        if (originalSize < thresholdBytes) {
            return new CompressionResult(payload, false, originalSize, originalSize);
        }

        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream(originalSize);
            try (GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream)) {
                gzipStream.write(uncompressedBytes);
            }

            byte[] compressedBytes = byteStream.toByteArray();
            int compressedSize = compressedBytes.length;

            if (compressedSize >= originalSize) {
                log.debug("Compression ineffective: original={}, compressed={}. Storing uncompressed.",
                        originalSize, compressedSize);
                return new CompressionResult(payload, false, originalSize, originalSize);
            }

            String base64Compressed = Base64.getEncoder().encodeToString(compressedBytes);
            double ratio = (1.0 - ((double) compressedSize / originalSize)) * 100;

            log.debug("Payload compressed: {} -> {} bytes ({}% reduction)",
                    originalSize, compressedSize, String.format("%.1f", ratio));

            return new CompressionResult(base64Compressed, true, originalSize, compressedSize);
        } catch (IOException e) {
            log.warn("Compression failed, storing uncompressed: {}", e.getMessage());
            return new CompressionResult(payload, false, originalSize, originalSize);
        }
    }

    public static String decompress(String payload, boolean isCompressed) {
        if (!isCompressed || payload == null || payload.isEmpty()) {
            return payload;
        }

        try {
            byte[] compressedBytes = Base64.getDecoder().decode(payload);
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();

            try (GZIPInputStream gzipStream = new GZIPInputStream(new ByteArrayInputStream(compressedBytes))) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = gzipStream.read(buffer)) > 0) {
                    byteStream.write(buffer, 0, len);
                }
            }

            return byteStream.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Decompression failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to decompress payload", e);
        }
    }

    public record CompressionResult(
            String payload,
            boolean compressed,
            int originalSize,
            int compressedSize
    ) {
        public double compressionRatio() {
            if (originalSize == 0) return 0.0;
            return (1.0 - ((double) compressedSize / originalSize)) * 100;
        }
    }
}
