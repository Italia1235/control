package ru.rasulov.workflow.impl.edit;

import com.atlassian.plugin.Plugin;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.plugin.PluginInformation;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.edit.ScriptRunnerStatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The ScriptRunner gate: only an installed, enabled ScriptRunner whose exact version is on the verified list passes. */
public class ScriptRunnerGateTest {

    private final PluginAccessor plugins = mock(PluginAccessor.class);
    private final ScriptRunnerGate gate = new ScriptRunnerGate(plugins);

    private void installed(String version, boolean enabled) {
        Plugin plugin = mock(Plugin.class);
        PluginInformation information = mock(PluginInformation.class);
        when(information.getVersion()).thenReturn(version);
        when(plugin.getPluginInformation()).thenReturn(information);
        when(plugins.getPlugin(ScriptRunnerGate.PLUGIN_KEY)).thenReturn(plugin);
        when(plugins.isPluginEnabled(ScriptRunnerGate.PLUGIN_KEY)).thenReturn(enabled);
    }


    @Test
    public void anEnabledScriptRunnerOfAVerifiedVersionPasses() {
        installed("9.46.0", true);

        ScriptRunnerStatus status = gate.status();

        assertTrue(status.isInstalled());
        assertTrue(status.isEnabled());
        assertEquals("9.46.0", status.getVersion());
        assertTrue(status.isSupported());
        assertNull(status.getReason());
    }

    @Test
    public void aVersionThatDiffersOnlyInThePatchPartIsNotVerified() {
        installed("9.46.1", true);

        ScriptRunnerStatus status = gate.status();

        assertTrue(status.isInstalled());
        assertEquals("9.46.1", status.getVersion());
        assertFalse(status.isSupported());
        assertTrue(status.getReason(), status.getReason().contains("9.46.1"));
        assertTrue(status.getReason(), status.getReason().contains("9.46.0"));
    }

    @Test
    public void everyOtherVersionAndEverySpellingOfTheVerifiedOneIsNotVerified() {
        for (String version : new String[] {"9.45.0", "9.47.0", "10.0.0", "9.46", "9.46.0-SNAPSHOT", " 9.46.0", "", "unknown"}) {
            installed(version, true);
            assertFalse(version, gate.status().isSupported());
        }
    }

    @Test
    public void aVersionlessInstallationIsNotVerified() {
        installed(null, true);

        ScriptRunnerStatus status = gate.status();

        assertTrue(status.isInstalled());
        assertNull(status.getVersion());
        assertFalse(status.isSupported());
        assertTrue(status.getReason(), status.getReason().contains("9.46.0"));
    }

    @Test
    public void aDisabledScriptRunnerIsReportedAsInstalledButDoesNotPassEvenWithAVerifiedVersion() {
        installed("9.46.0", false);

        ScriptRunnerStatus status = gate.status();

        assertTrue(status.isInstalled());
        assertFalse(status.isEnabled());
        assertEquals("9.46.0", status.getVersion());
        assertFalse(status.isSupported());
        assertTrue(status.getReason(), status.getReason().toLowerCase().contains("not enabled"));
    }

    @Test
    public void aMissingScriptRunnerIsReportedAsNotInstalled() {
        when(plugins.getPlugin(ScriptRunnerGate.PLUGIN_KEY)).thenReturn(null);

        ScriptRunnerStatus status = gate.status();

        assertFalse(status.isInstalled());
        assertFalse(status.isEnabled());
        assertNull(status.getVersion());
        assertFalse(status.isSupported());
        assertTrue(status.getReason(), status.getReason().toLowerCase().contains("not installed"));
    }
    @Test
    public void conditionsRequireAnEnabledPluginWithAnExactlyCapturedVersion() {
        installed("9.46.0", true);
        assertTrue(gate.conditionStatus().isSupported());

        installed("9.46.0", false);
        ScriptRunnerStatus disabled = gate.conditionStatus();
        assertTrue(disabled.isInstalled());
        assertFalse(disabled.isEnabled());
        assertFalse(disabled.isSupported());

        for (String version : new String[] {"9.46.1", "9.46", "9.46.0-SNAPSHOT", null}) {
            installed(version, true);
            ScriptRunnerStatus condition = gate.conditionStatus();
            assertTrue(condition.isInstalled());
            assertTrue(condition.isEnabled());
            assertFalse(condition.isSupported());
            assertTrue(condition.getReason(), condition.getReason().contains("Project role(s) condition"));
            assertTrue(condition.getReason(), condition.getReason().contains("9.46.0"));
            assertTrue(gate.status().getReason().contains("Field(s) required validator"));
        }

        when(plugins.getPlugin(ScriptRunnerGate.PLUGIN_KEY)).thenReturn(null);
        ScriptRunnerStatus missing = gate.conditionStatus();
        assertFalse(missing.isInstalled());
        assertFalse(missing.isEnabled());
        assertFalse(missing.isSupported());
        assertTrue(missing.getReason(), missing.getReason().contains("not installed"));
    }
}
