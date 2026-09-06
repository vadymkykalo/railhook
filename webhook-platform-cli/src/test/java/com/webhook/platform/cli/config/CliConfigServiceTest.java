package com.webhook.platform.cli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnDefaultConfigWhenFileDoesNotExist() {
        Path configPath = tempDir.resolve("nonexistent/config.json");
        CliConfigService service = new CliConfigService(configPath);

        CliConfig config = service.load();

        assertNotNull(config);
        assertEquals("http://localhost:8080", config.getBackendUrl());
        assertNull(config.getAccessToken());
        assertFalse(config.isAuthenticated());
    }

    @Test
    void shouldSaveAndLoadConfig() {
        Path configPath = tempDir.resolve("config.json");
        CliConfigService service = new CliConfigService(configPath);

        CliConfig config = new CliConfig();
        config.setBackendUrl("https://api.example.com");
        config.setAccessToken("test-token-123");
        config.setRefreshToken("refresh-token-456");
        config.setUserId("user-001");
        config.setOrganizationId("org-001");
        config.setActiveProjectId("proj-001");

        service.save(config);

        CliConfig loaded = service.load();
        assertEquals("https://api.example.com", loaded.getBackendUrl());
        assertEquals("test-token-123", loaded.getAccessToken());
        assertEquals("refresh-token-456", loaded.getRefreshToken());
        assertEquals("user-001", loaded.getUserId());
        assertEquals("org-001", loaded.getOrganizationId());
        assertEquals("proj-001", loaded.getActiveProjectId());
        assertTrue(loaded.isAuthenticated());
    }

    @Test
    void shouldClearConfig() {
        Path configPath = tempDir.resolve("config.json");
        CliConfigService service = new CliConfigService(configPath);

        CliConfig config = new CliConfig();
        config.setAccessToken("some-token");
        service.save(config);

        assertTrue(configPath.toFile().exists());

        service.clear();

        assertFalse(configPath.toFile().exists());
        CliConfig loaded = service.load();
        assertFalse(loaded.isAuthenticated());
    }

    @Test
    void shouldHandleCorruptedConfigGracefully() throws Exception {
        Path configPath = tempDir.resolve("config.json");
        java.nio.file.Files.writeString(configPath, "NOT VALID JSON {{{");

        CliConfigService service = new CliConfigService(configPath);
        CliConfig config = service.load();

        assertNotNull(config);
        assertEquals("http://localhost:8080", config.getBackendUrl());
    }

    @Test
    void shouldCreateParentDirectoriesOnSave() {
        Path configPath = tempDir.resolve("deep/nested/dir/config.json");
        CliConfigService service = new CliConfigService(configPath);

        CliConfig config = new CliConfig();
        config.setBackendUrl("https://test.example.com");
        service.save(config);

        assertTrue(configPath.toFile().exists());
        CliConfig loaded = service.load();
        assertEquals("https://test.example.com", loaded.getBackendUrl());
    }

    @Test
    void shouldIgnoreUnknownFieldsInConfig() throws Exception {
        Path configPath = tempDir.resolve("config.json");
        java.nio.file.Files.writeString(configPath,
                "{\"backendUrl\":\"https://test.com\",\"unknownField\":\"value\",\"accessToken\":\"tok\"}");

        CliConfigService service = new CliConfigService(configPath);
        CliConfig config = service.load();

        assertEquals("https://test.com", config.getBackendUrl());
        assertEquals("tok", config.getAccessToken());
    }

    @Test
    void shouldGenerateCorrectWsUrl() {
        CliConfig config = new CliConfig();

        config.setBackendUrl("http://localhost:8080");
        assertEquals("ws://localhost:8080", config.getWsUrl());

        config.setBackendUrl("https://api.example.com");
        assertEquals("wss://api.example.com", config.getWsUrl());
    }

    @Test
    void shouldReturnConfigPath() {
        Path configPath = tempDir.resolve("config.json");
        CliConfigService service = new CliConfigService(configPath);
        assertEquals(configPath, service.getConfigPath());
    }
}
