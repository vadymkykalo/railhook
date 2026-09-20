package com.webhook.platform.common.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * One engine per process, in both services.
 *
 * <p>It is a singleton because the parsed-code cache lives on it: the second run of a script
 * costs milliseconds where the first costs a second, and a per-call engine would pay the second
 * every time. It is built lazily inside itself, so a deployment that never runs a JavaScript
 * transformation never brings GraalJS up at all.
 *
 * <p>Both api and worker scan {@code com.webhook.platform.common}, which is how the preview and
 * the real delivery end up holding the same implementation with the same limits.
 */
@Configuration
public class ScriptTransformConfiguration {

    @Bean(destroyMethod = "close")
    public JavaScriptTransformEngine javaScriptTransformEngine(
            ObjectMapper objectMapper,
            @Value("${transform.script.timeout-ms:2000}") long timeoutMs,
            @Value("${transform.script.max-memory-mb:128}") long maxMemoryMb,
            @Value("${transform.script.max-output-bytes:1048576}") int maxOutputBytes,
            @Value("${transform.script.max-console-lines:100}") int maxConsoleLines,
            @Value("${transform.script.max-chars:65536}") int maxChars) {

        return new JavaScriptTransformEngine(objectMapper, new ScriptLimits(
                Duration.ofMillis(timeoutMs),
                maxMemoryMb * 1024 * 1024,
                maxOutputBytes,
                maxConsoleLines,
                maxChars));
    }
}
