package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What Control knows about ScriptRunner for Jira: installed and enabled, its version, and whether that exact version is
 * one whose validator format was captured ({@code supported}); {@code reason} says why it is not.
 */
public final class ScriptRunnerStatus {
    private final boolean installed;
    private final boolean enabled;
    private final String version;
    private final boolean supported;
    private final String reason;

    public ScriptRunnerStatus(boolean installed, boolean enabled, String version, boolean supported, String reason) {
        this.installed = installed;
        this.enabled = enabled;
        this.version = version;
        this.supported = supported;
        this.reason = reason;
    }

    @JsonProperty("installed") public boolean isInstalled() { return installed; }
    @JsonProperty("enabled") public boolean isEnabled() { return enabled; }
    @JsonProperty("version") public String getVersion() { return version; }
    @JsonProperty("supported") public boolean isSupported() { return supported; }
    /** Why ScriptRunner-backed rules are unavailable to configure_workflow; {@code null} when supported. */
    @JsonProperty("reason") public String getReason() { return reason; }
}
