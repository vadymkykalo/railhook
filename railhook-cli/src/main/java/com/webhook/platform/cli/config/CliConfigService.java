package com.webhook.platform.cli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Reads/writes CLI configuration from ~/.config/railhook/config.json.
 * File permissions are set to 600 (owner-only) to protect tokens.
 */
public class CliConfigService {

    private static final Logger log = LoggerFactory.getLogger(CliConfigService.class);
    private static final String CONFIG_DIR = "railhook";
    private static final String CONFIG_FILE = "config.json";

    private final ObjectMapper objectMapper;
    private final Path configPath;

    public CliConfigService() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.configPath = resolveConfigPath();
    }

    public CliConfigService(Path configPath) {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.configPath = configPath;
    }

    public CliConfig load() {
        if (!Files.exists(configPath)) {
            return new CliConfig();
        }
        try {
            return objectMapper.readValue(configPath.toFile(), CliConfig.class);
        } catch (IOException e) {
            log.warn("Failed to read config from {}: {}", configPath, e.getMessage());
            return new CliConfig();
        }
    }

    public void save(CliConfig config) {
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writeValue(configPath.toFile(), config);
            trySetOwnerOnly(configPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config to " + configPath + ": " + e.getMessage(), e);
        }
    }

    public Path getConfigPath() {
        return configPath;
    }

    public void clear() {
        try {
            Files.deleteIfExists(configPath);
        } catch (IOException e) {
            log.warn("Failed to delete config: {}", e.getMessage());
        }
    }

    private static Path resolveConfigPath() {
        String configHome = System.getenv("XDG_CONFIG_HOME");
        if (configHome != null && !configHome.isBlank()) {
            return Path.of(configHome, CONFIG_DIR, CONFIG_FILE);
        }
        String railhookConfig = System.getenv("RAILHOOK_CONFIG");
        if (railhookConfig != null && !railhookConfig.isBlank()) {
            return Path.of(railhookConfig);
        }
        return Path.of(System.getProperty("user.home"), ".config", CONFIG_DIR, CONFIG_FILE);
    }

    private static void trySetOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception e) {
            // Non-POSIX system (e.g. Windows) — skip
        }
    }
}
