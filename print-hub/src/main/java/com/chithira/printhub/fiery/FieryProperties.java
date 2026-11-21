package com.chithira.printhub.fiery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code printhub.fiery.*}: how the hub reaches the Fiery and reads job states. */
@ConfigurationProperties("printhub.fiery")
public class FieryProperties {

    /** False = no status polling and no paper sync (jobs stay as the desktops reported them). */
    private boolean enabled;
    private String baseUrl = "https://192.168.1.50";
    private String basePath = "/live/api/v5";
    private String apiKey = "";
    private String username = "";
    private String password = "";
    private boolean trustSelfSignedCertificate = true;
    private int pollSeconds = 60;
    private int paperSyncHours = 6;
    private int connectTimeoutSeconds = 10;
    private int requestTimeoutSeconds = 30;
    /** Dotted path to the job state in {@code GET /jobs/{id}}, e.g. {@code data.item.state}. */
    private String statusPath = "data.item.state";
    /** Fiery state (lower case) → hub status name. */
    private Map<String, String> statusMap = new LinkedHashMap<>();
    private int missingChecksBeforeRemoved = 2;

    /** True when polling is on and the address and login are filled in. */
    public boolean isConfigured() {
        return enabled && !baseUrl.isBlank() && !apiKey.isBlank() && !username.isBlank() && !password.isBlank();
    }

    /** Full URL of an API path, e.g. {@code url("/jobs/1001")}. */
    public String url(String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String prefix = basePath.startsWith("/") ? basePath : "/" + basePath;
        return base + (prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix) + path;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl == null ? "" : baseUrl.trim(); }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath == null ? "" : basePath.trim(); }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.trim(); }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username == null ? "" : username.trim(); }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password == null ? "" : password; }
    public boolean isTrustSelfSignedCertificate() { return trustSelfSignedCertificate; }
    public void setTrustSelfSignedCertificate(boolean trust) { this.trustSelfSignedCertificate = trust; }
    public int getPollSeconds() { return pollSeconds; }
    public void setPollSeconds(int pollSeconds) { this.pollSeconds = Math.max(10, pollSeconds); }
    public int getPaperSyncHours() { return paperSyncHours; }
    public void setPaperSyncHours(int paperSyncHours) { this.paperSyncHours = Math.max(1, paperSyncHours); }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int seconds) { this.connectTimeoutSeconds = seconds; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int seconds) { this.requestTimeoutSeconds = seconds; }
    public String getStatusPath() { return statusPath; }
    public void setStatusPath(String statusPath) { this.statusPath = statusPath; }
    public Map<String, String> getStatusMap() { return statusMap; }
    public void setStatusMap(Map<String, String> statusMap) { this.statusMap = statusMap; }
    public int getMissingChecksBeforeRemoved() { return missingChecksBeforeRemoved; }
    public void setMissingChecksBeforeRemoved(int count) { this.missingChecksBeforeRemoved = Math.max(1, count); }
}
