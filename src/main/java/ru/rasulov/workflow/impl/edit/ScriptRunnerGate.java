package ru.rasulov.workflow.impl.edit;

import com.atlassian.plugin.Plugin;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.plugin.PluginInformation;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.workflow.api.dto.edit.ScriptRunnerStatus;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;

/**
 * Whether the installed ScriptRunner has a captured format for validators or project-role conditions. Looked up
 * through {@link PluginAccessor} by plugin key only: no ScriptRunner class is linked or imported.
 */
@Named
public class ScriptRunnerGate {

    public static final String PLUGIN_KEY = "com.onresolve.jira.groovy.groovyrunner";
    /** Add a version only after capturing the validator samples on a stand. */
    public static final List<String> VERIFIED_VALIDATOR_VERSIONS = List.of("9.46.0");
    /** Independent of validators: project-role samples must be captured separately. */
    public static final List<String> VERIFIED_CONDITION_VERSIONS = List.of("9.46.0");

    private final PluginAccessor plugins;

    @Inject
    public ScriptRunnerGate(@ComponentImport PluginAccessor plugins) {
        this.plugins = plugins;
    }

    public ScriptRunnerStatus status() {
        return status(VERIFIED_VALIDATOR_VERSIONS, "Field(s) required validator");
    }

    public ScriptRunnerStatus conditionStatus() {
        return status(VERIFIED_CONDITION_VERSIONS, "Project role(s) condition");
    }

    private ScriptRunnerStatus status(List<String> verifiedVersions, String format) {
        Plugin plugin = plugins.getPlugin(PLUGIN_KEY);
        if (plugin == null) {
            return new ScriptRunnerStatus(false, false, null, false, "ScriptRunner for Jira (plugin " + PLUGIN_KEY
                    + ") is not installed. Verified versions: " + verifiedVersions + ".");
        }
        PluginInformation information = plugin.getPluginInformation();
        String version = information == null ? null : information.getVersion();
        String shown = version == null ? "(version unknown)" : version;
        if (!plugins.isPluginEnabled(PLUGIN_KEY)) {
            return new ScriptRunnerStatus(true, false, version, false, "ScriptRunner for Jira " + shown
                    + " is installed but not enabled. Verified versions: " + verifiedVersions + ".");
        }
        if (version == null || !verifiedVersions.contains(version)) {
            return new ScriptRunnerStatus(true, true, version, false, "The installed ScriptRunner for Jira has version "
                    + (version == null ? "unknown" : "'" + version + "'") + ", but the format of its " + format
                    + " was captured only for versions " + verifiedVersions + " (an exact match is required: "
                    + "even a different patch version is not accepted).");
        }
        return new ScriptRunnerStatus(true, true, version, true, null);
    }
}
