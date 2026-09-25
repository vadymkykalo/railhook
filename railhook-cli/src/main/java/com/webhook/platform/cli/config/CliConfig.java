package com.webhook.platform.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CliConfig {

    private String backendUrl;
    private String accessToken;
    private String refreshToken;
    private String organizationId;
    private String userId;
    private String activeProjectId;

    /** Null means the root-level fields are the active configuration. */
    private String activeProfile;
    private Map<String, ProfileConfig> profiles;

    public CliConfig() {
        this.backendUrl = "http://localhost:8080";
    }

    public String getBackendUrl() { return backendUrl; }
    public void setBackendUrl(String backendUrl) { this.backendUrl = backendUrl; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getActiveProjectId() { return activeProjectId; }
    public void setActiveProjectId(String activeProjectId) { this.activeProjectId = activeProjectId; }

    public String getActiveProfile() { return activeProfile; }
    public void setActiveProfile(String activeProfile) { this.activeProfile = activeProfile; }

    public Map<String, ProfileConfig> getProfiles() { return profiles; }
    public void setProfiles(Map<String, ProfileConfig> profiles) { this.profiles = profiles; }

    public boolean isAuthenticated() {
        return accessToken != null && !accessToken.isBlank();
    }

    public String getWsUrl() {
        return backendUrl.replace("https://", "wss://")
                .replace("http://", "ws://");
    }

    public Map<String, ProfileConfig> ensureProfiles() {
        if (profiles == null) profiles = new LinkedHashMap<>();
        return profiles;
    }

    public ProfileConfig toProfile() {
        ProfileConfig p = new ProfileConfig();
        p.setBackendUrl(backendUrl);
        p.setAccessToken(accessToken);
        p.setRefreshToken(refreshToken);
        p.setOrganizationId(organizationId);
        p.setUserId(userId);
        p.setActiveProjectId(activeProjectId);
        return p;
    }

    public void applyProfile(ProfileConfig p) {
        this.backendUrl = p.getBackendUrl() != null ? p.getBackendUrl() : "http://localhost:8080";
        this.accessToken = p.getAccessToken();
        this.refreshToken = p.getRefreshToken();
        this.organizationId = p.getOrganizationId();
        this.userId = p.getUserId();
        this.activeProjectId = p.getActiveProjectId();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProfileConfig {
        private String backendUrl;
        private String accessToken;
        private String refreshToken;
        private String organizationId;
        private String userId;
        private String activeProjectId;

        public String getBackendUrl() { return backendUrl; }
        public void setBackendUrl(String backendUrl) { this.backendUrl = backendUrl; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public String getOrganizationId() { return organizationId; }
        public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getActiveProjectId() { return activeProjectId; }
        public void setActiveProjectId(String activeProjectId) { this.activeProjectId = activeProjectId; }
    }
}
