package com.webhook.platform.common.dto.tunnel;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * How a body crosses the tunnel's WebSocket, in either direction.
 *
 * <p>The messages are JSON, and the body always travelled as a string: decoded on one side and
 * encoded back on the other. That is exact for text in the charset both sides agree on and wrong
 * for everything else — a gzip, protobuf or image body, or text that is not valid in its declared
 * charset, came out as different bytes, and a signature over the original no longer matched.
 *
 * <p>So a body the string cannot carry exactly also travels as base64 in {@code bodyBase64}, and
 * a reader prefers that. The string stays populated as before in every case: a CLI already
 * installed on a developer's machine, or an older self-hosted server, reads only it. Base64 is
 * left out when the string is exact, which keeps a text body's message the size it always was —
 * both sides have a size limit on a WebSocket message, and an installed CLI's cannot be raised.
 * Whether it is exact is decided with the charset the {@code Content-Type} header names, which
 * travels with the message, so the reader encodes the string back with the same one.
 */
public final class TunnelBody {

    private TunnelBody() {
    }

    /** The charset {@code Content-Type} names, UTF-8 when it names none or one this JVM lacks. */
    public static Charset charsetOf(Map<String, String> headers) {
        if (headers == null) {
            return StandardCharsets.UTF_8;
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() != null && header.getKey().equalsIgnoreCase("Content-Type")) {
                return charsetOfContentType(header.getValue());
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset charsetOfContentType(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        for (String parameter : contentType.split(";")) {
            String[] pair = parameter.split("=", 2);
            if (pair.length == 2 && pair[0].trim().toLowerCase(Locale.ROOT).equals("charset")) {
                String name = pair[1].trim();
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** The {@code body} string, exactly as it has always been written: the bytes decoded. */
    static String text(byte[] raw, Charset charset) {
        return raw == null ? null : new String(raw, charset);
    }

    /** The {@code bodyBase64} field: null when {@link #text} already carries these bytes exactly. */
    static String base64IfLossy(byte[] raw, Charset charset) {
        if (raw == null) {
            return null;
        }
        boolean exact = charset.canEncode() && Arrays.equals(new String(raw, charset).getBytes(charset), raw);
        return exact ? null : Base64.getEncoder().encodeToString(raw);
    }

    /** What a reader sends on: the base64 bytes when present, else the string encoded back. */
    static byte[] bytes(String body, String bodyBase64, Map<String, String> headers) {
        if (bodyBase64 != null) {
            return Base64.getDecoder().decode(bodyBase64);
        }
        return body == null ? null : body.getBytes(charsetOf(headers));
    }
}
