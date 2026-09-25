package com.webhook.platform.common.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** One engine per process: it holds the parsed-code cache. */
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
