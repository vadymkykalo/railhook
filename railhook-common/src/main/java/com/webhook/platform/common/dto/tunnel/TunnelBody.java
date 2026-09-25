package com.webhook.platform.common.dto.tunnel;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * The tunnel's JSON messages always carried the body as a decoded string, which corrupts binary
 * bodies and text invalid in its charset, and breaks signatures. A lossy body now also travels
 * as {@code bodyBase64}, which readers prefer. The string is still always set because installed
 * CLIs and older servers read only it. Base64 is omitted when the string is exact, since both
 * sides cap WebSocket message size and an installed CLI's cap cannot be raised.
 */
public final class TunnelBody {

    private TunnelBody() {
    }

    /** UTF-8 when none is named or this JVM lacks it. */
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

    static String text(byte[] raw, Charset charset) {
        return raw == null ? null : new String(raw, charset);
    }

    /** Null when {@link #text} already carries these bytes exactly. */
    static String base64IfLossy(byte[] raw, Charset charset) {
        if (raw == null) {
            return null;
        }
        boolean exact = charset.canEncode() && Arrays.equals(new String(raw, charset).getBytes(charset), raw);
        return exact ? null : Base64.getEncoder().encodeToString(raw);
    }

    static byte[] bytes(String body, String bodyBase64, Map<String, String> headers) {
        if (bodyBase64 != null) {
            return Base64.getDecoder().decode(bodyBase64);
        }
        return body == null ? null : body.getBytes(charsetOf(headers));
    }
}
