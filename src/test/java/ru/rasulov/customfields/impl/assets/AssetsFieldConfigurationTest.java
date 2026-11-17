package ru.rasulov.customfields.impl.assets;

import com.atlassian.plugin.PluginAccessor;
import com.atlassian.plugin.module.ContainerAccessor;
import com.atlassian.plugin.module.ContainerManagedPlugin;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.customfields.impl.validation.FieldFailureException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class AssetsFieldConfigurationTest {
    private static final String PLUGIN_KEY = "com.riadalabs.jira.plugins.insight";
    private static final String API_BASE = PLUGIN_KEY + ".channel.external.api.facade.";
    private static final String SERVICE_CLASS = PLUGIN_KEY + ".services.core.CustomFieldService";
    private static final long CONFIG_ID = 10501L;
    private static final String ORIGINAL_AQL = "objectType = Server";
    private static final String NEW_AQL = "objectType = Laptop";

    private PluginAccessor plugins;
    private ContainerManagedPlugin plugin;
    private ContainerAccessor container;
    private ObjectSchemaFacade schemas;
    private IQLFacade aql;
    private FieldService fields;
    private FakeConnectBean original;
    private AssetsFieldConfiguration configuration;

    @Before
    public void setUp() throws Exception {
        plugins = mock(PluginAccessor.class);
        plugin = mock(ContainerManagedPlugin.class);
        container = mock(ContainerAccessor.class);
        schemas = mock(ObjectSchemaFacade.class);
        aql = mock(IQLFacade.class);
        original = new FakeConnectBean(4, ORIGINAL_AQL);
        original.setFilterIssueScopeIql("Owner = ${reporter}");
        original.setMultiple(true);
        original.setObjectTypeIds(new ArrayList<>(Arrays.asList(12, 13)));
        fields = new FieldService(CONFIG_ID, original);
        when(plugins.getEnabledPlugin(PLUGIN_KEY)).thenReturn(plugin);
        when(plugin.getContainerAccessor()).thenReturn(container);
        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if ((API_BASE + "ObjectSchemaFacade").equals(name)) return ObjectSchemaFacade.class;
            if ((API_BASE + "IQLFacade").equals(name)) return IQLFacade.class;
            if (SERVICE_CLASS.equals(name)) return CustomFieldService.class;
            throw new ClassNotFoundException(name);
        }).when(plugin).loadClass(anyString(), any());
        when(container.getBeansOfType(ObjectSchemaFacade.class)).thenReturn(Collections.singletonList(schemas));
        when(container.getBeansOfType(IQLFacade.class)).thenReturn(Collections.singletonList(aql));
        when(container.getBeansOfType(CustomFieldService.class))
                .thenReturn(Collections.<CustomFieldService>singletonList(fields));
        when(schemas.loadObjectSchemaBean(7)).thenReturn(new ObjectSchemaBean(7));
        configuration = new AssetsFieldConfiguration(plugins);
    }

    @Test
    public void missingSchemaFailsValidationWithoutWritingConfiguration() {
        FieldFailureException failure = expectValidationFailure(AssetsFieldConfiguration.TYPE_KEY,
                new AssetsFieldSettings(99, NEW_AQL));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        verify(schemas).loadObjectSchemaBean(99);
        verifyNoInteractions(aql);
        assertUnwritten();
    }

    @Test
    public void invalidAqlFailsValidationWithoutWritingConfiguration() throws Exception {
        doThrow(new IllegalArgumentException("Invalid AQL"))
                .when(aql).validateIQL(7, "objectType =");

        FieldFailureException failure = expectValidationFailure(AssetsFieldConfiguration.TYPE_KEY,
                new AssetsFieldSettings(7, "objectType ="));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        verify(schemas).loadObjectSchemaBean(7);
        verify(aql).validateIQL(7, "objectType =");
        assertUnwritten();
    }

    @Test
    public void settingsOnNonAssetsFieldFailWithoutLoadingPluginOrWritingConfiguration() {
        FieldFailureException failure = expectValidationFailure(
                "com.atlassian.jira.plugin.system.customfieldtypes:textfield",
                new AssetsFieldSettings(7, NEW_AQL));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        verifyNoInteractions(plugins, plugin, container, schemas, aql);
        assertUnwritten();
    }

    @Test
    public void absentAssetsPluginFailsValidationWithoutWritingConfiguration() {
        when(plugins.getEnabledPlugin(PLUGIN_KEY)).thenReturn(null);

        FieldFailureException failure = expectValidationFailure(AssetsFieldConfiguration.TYPE_KEY,
                new AssetsFieldSettings(7, NEW_AQL));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        verifyNoInteractions(schemas, aql);
        assertUnwritten();
    }

    @Test
    public void nullSettingsDoNotRequireAssetsPlugin() {
        when(plugins.getEnabledPlugin(PLUGIN_KEY)).thenReturn(null);

        configuration.validate(AssetsFieldConfiguration.TYPE_KEY, null);

        verifyNoInteractions(plugins, plugin, container, schemas, aql);
        assertUnwritten();
    }

    @Test
    public void validSettingsValidateAqlAgainstTheirSchemaWithoutWriting() throws Exception {
        configuration.validate(AssetsFieldConfiguration.TYPE_KEY, new AssetsFieldSettings(7, NEW_AQL));

        verify(schemas).loadObjectSchemaBean(7);
        verify(aql).validateIQL(7, NEW_AQL);
        assertUnwritten();
    }

    @Test
    public void absentOrBlankFilterDoesNotInvokeAqlValidation() {
        configuration.validate(AssetsFieldConfiguration.TYPE_KEY, new AssetsFieldSettings(7, null));
        configuration.validate(AssetsFieldConfiguration.TYPE_KEY, new AssetsFieldSettings(7, "   "));

        verifyNoInteractions(aql);
        assertUnwritten();
    }

    @Test
    public void creationInitializesMissingAssetsConfigurationWithFieldAndContextIdentity() throws Exception {
        fields.current = null;
        org.mockito.Mockito.doReturn(FakeConnectBean.class).when(plugin).loadClass(
                PLUGIN_KEY + ".services.model.customfield.DefaultCustomFieldConnectBean", AssetsFieldConfiguration.class);
        String before = configuration.snapshot(CONFIG_ID);
        configuration.configure(CONFIG_ID, 10001L, new AssetsFieldSettings(7, NEW_AQL));
        assertEquals(Long.valueOf(CONFIG_ID), fields.current.getCustomFieldConfigId());
        assertEquals(Long.valueOf(10001L), fields.current.getCustomFieldId());
        assertEquals(Integer.valueOf(7), fields.current.getObjectSchemaId());
        assertEquals(NEW_AQL, fields.current.getScopeIql());
        AssetsConfigurationUndoHandler undo = new AssetsConfigurationUndoHandler(configuration);
        ru.rasulov.common.impl.undo.UndoStep step = new ru.rasulov.common.impl.undo.UndoStep(
                1L, "cf.assets", "cf.assets:" + CONFIG_ID, before, configuration.snapshot(CONFIG_ID), "APPLIED");
        assertTrue(undo.conflict(step).contains("retained"));
    }

    @Test
    public void undoBlocksForeignSettingsAndRestoresAnUnchangedRecordedConfiguration() {
        String before = configuration.snapshot(CONFIG_ID);
        configuration.configure(CONFIG_ID, 10001L, new AssetsFieldSettings(7, NEW_AQL));
        ru.rasulov.common.impl.undo.UndoStep step = new ru.rasulov.common.impl.undo.UndoStep(
                1L, "cf.assets", "cf.assets:" + CONFIG_ID, before, configuration.snapshot(CONFIG_ID), "APPLIED");
        AssetsConfigurationUndoHandler undo = new AssetsConfigurationUndoHandler(configuration);
        fields.current.setMultiple(false);
        assertTrue(undo.conflict(step).contains("changed"));
        try { undo.undo(step, null); fail("Foreign configuration must be retained"); }
        catch (IllegalStateException expected) { assertEquals(NEW_AQL, fields.current.getScopeIql()); }
        fields.current.setMultiple(true);
        undo.undo(step, null);
        assertEquals(before, configuration.snapshot(CONFIG_ID));
    }

    @Test
    public void configureClonesDefaultBeanAndPreservesUnrelatedSettings() {
        configuration.configure(CONFIG_ID, 10001L, new AssetsFieldSettings(7, NEW_AQL));

        assertEquals(1, fields.writes);
        assertSame(original, fields.lastBefore);
        assertSame(fields.current, fields.lastAfter);
        assertNotSame(original, fields.current);
        assertEquals(Integer.valueOf(4), original.getObjectSchemaId());
        assertEquals(ORIGINAL_AQL, original.getScopeIql());
        assertEquals(Integer.valueOf(7), fields.current.getObjectSchemaId());
        assertEquals(NEW_AQL, fields.current.getScopeIql());
        assertEquals("Owner = ${reporter}", fields.current.getFilterIssueScopeIql());
        assertTrue(fields.current.isMultiple());
        assertEquals(Arrays.asList(12, 13), fields.current.getObjectTypeIds());
    }

    @Test
    public void snapshotDetectsForeignChangesToUnrelatedStringBooleanAndCollectionSettings() {
        String before = configuration.snapshot(CONFIG_ID);
        assertEquals(before, configuration.snapshot(CONFIG_ID));

        fields.current.setFilterIssueScopeIql("Owner = ${assignee}");
        assertFalse(before.equals(configuration.snapshot(CONFIG_ID)));
        fields.current.setFilterIssueScopeIql("Owner = ${reporter}");
        assertEquals(before, configuration.snapshot(CONFIG_ID));

        fields.current.setMultiple(false);
        assertFalse(before.equals(configuration.snapshot(CONFIG_ID)));
        fields.current.setMultiple(true);
        assertEquals(before, configuration.snapshot(CONFIG_ID));

        fields.current.getObjectTypeIds().add(14);
        assertFalse(before.equals(configuration.snapshot(CONFIG_ID)));
        assertEquals(0, fields.writes);
    }

    @Test
    public void restoreResetsSchemaAndAqlButPreservesCurrentUnrelatedSettings() {
        String before = configuration.snapshot(CONFIG_ID);
        configuration.configure(CONFIG_ID, 10001L, new AssetsFieldSettings(7, NEW_AQL));
        FakeConnectBean configured = fields.current;
        configured.setFilterIssueScopeIql("Owner = ${assignee}");
        configured.setMultiple(false);
        configured.setObjectTypeIds(new ArrayList<>(Collections.singletonList(21)));

        configuration.restore(before);

        assertEquals(2, fields.writes);
        assertSame(configured, fields.lastBefore);
        assertNotSame(configured, fields.current);
        assertEquals(Integer.valueOf(7), configured.getObjectSchemaId());
        assertEquals(NEW_AQL, configured.getScopeIql());
        assertEquals(Integer.valueOf(4), fields.current.getObjectSchemaId());
        assertEquals(ORIGINAL_AQL, fields.current.getScopeIql());
        assertEquals("Owner = ${assignee}", fields.current.getFilterIssueScopeIql());
        assertFalse(fields.current.isMultiple());
        assertEquals(Collections.singletonList(21), fields.current.getObjectTypeIds());
    }

    @Test
    public void restoreCanResetPreviouslyAbsentSchemaAndFilter() {
        original.setObjectSchemaId(null);
        original.setScopeIql(null);
        String before = configuration.snapshot(CONFIG_ID);
        configuration.configure(CONFIG_ID, 10001L, new AssetsFieldSettings(7, NEW_AQL));

        configuration.restore(before);

        assertEquals(null, fields.current.getObjectSchemaId());
        assertEquals(null, fields.current.getScopeIql());
        assertEquals(before, configuration.snapshot(CONFIG_ID));
    }

    @Test
    public void snapshotWithoutSettingsIgnoresSchemaAndAqlButSeesEveryOtherProperty() {
        String before = AssetsFieldConfiguration.withoutSettings(configuration.snapshot(CONFIG_ID));
        configuration.configure(CONFIG_ID, 10001L, new AssetsFieldSettings(7, NEW_AQL));
        assertEquals(before, AssetsFieldConfiguration.withoutSettings(configuration.snapshot(CONFIG_ID)));

        fields.current.setFilterIssueScopeIql("Owner = ${assignee}");
        assertFalse(before.equals(AssetsFieldConfiguration.withoutSettings(configuration.snapshot(CONFIG_ID))));

        String uninitialized = "{\"bean\":null,\"configId\":" + CONFIG_ID + "}";
        assertEquals(uninitialized, AssetsFieldConfiguration.withoutSettings(uninitialized));
    }

    @Test
    public void settingsOfASnapshotAreTheStoredSchemaAndAqlAndNullWhenNotInitialized() {
        AssetsFieldSettings stored = configuration.settings(configuration.snapshot(CONFIG_ID));
        assertEquals(Integer.valueOf(4), stored.getObjectSchemaId());
        assertEquals(ORIGINAL_AQL, stored.getFilterScopeAql());

        original.setObjectSchemaId(null);
        original.setScopeIql(null);
        AssetsFieldSettings empty = configuration.settings(configuration.snapshot(CONFIG_ID));
        assertEquals(null, empty.getObjectSchemaId());
        assertEquals(null, empty.getFilterScopeAql());

        assertEquals(null, configuration.settings("{\"bean\":null,\"configId\":" + CONFIG_ID + "}"));
    }

    private FieldFailureException expectValidationFailure(String typeKey, AssetsFieldSettings settings) {
        try {
            configuration.validate(typeKey, settings);
            fail("Expected Assets settings validation failure");
            return null;
        } catch (FieldFailureException failure) {
            return failure;
        }
    }

    private void assertUnwritten() {
        assertEquals(0, fields.writes);
        assertSame(original, fields.current);
        assertEquals(Integer.valueOf(4), original.getObjectSchemaId());
        assertEquals(ORIGINAL_AQL, original.getScopeIql());
    }

    public interface ObjectSchemaFacade {
        ObjectSchemaBean loadObjectSchemaBean(int id);
    }

    public interface IQLFacade {
        void validateIQL(int schemaId, String query) throws Exception;
    }

    public interface CustomFieldService {
        FakeConnectBean loadCustomFieldConnectBean(long configId);
        void storeCustomFieldConnectBean(FakeConnectBean before, FakeConnectBean after);
    }

    public static final class ObjectSchemaBean {
        private final int id;

        public ObjectSchemaBean(int id) { this.id = id; }
        public int getId() { return id; }
    }

    public static final class FieldService implements CustomFieldService {
        private final long configId;
        private FakeConnectBean current;
        private FakeConnectBean lastBefore;
        private FakeConnectBean lastAfter;
        private int writes;

        public FieldService(long configId, FakeConnectBean current) {
            this.configId = configId;
            this.current = current;
        }

        @Override
        public FakeConnectBean loadCustomFieldConnectBean(long configId) {
            if (configId != this.configId) throw new IllegalArgumentException("Unknown config " + configId);
            return current;
        }

        @Override
        public void storeCustomFieldConnectBean(FakeConnectBean before, FakeConnectBean after) {
            assertSame("Write must identify the existing default bean", current, before);
            lastBefore = before;
            lastAfter = after;
            current = after;
            writes++;
        }
    }

    public static final class FakeConnectBean implements Cloneable {
        private Integer objectSchemaId;
        private String scopeIql;
        private String filterIssueScopeIql;
        private boolean multiple;
        private List<Integer> objectTypeIds;
        private Long customFieldId;
        private Long customFieldConfigId;

        public FakeConnectBean() {}
        public Long getCustomFieldId() { return customFieldId; }
        public void setCustomFieldId(Long value) { customFieldId = value; }
        public Long getCustomFieldConfigId() { return customFieldConfigId; }
        public void setCustomFieldConfigId(Long value) { customFieldConfigId = value; }

        public FakeConnectBean(Integer objectSchemaId, String scopeIql) {
            this.objectSchemaId = objectSchemaId;
            this.scopeIql = scopeIql;
        }

        public Integer getObjectSchemaId() { return objectSchemaId; }
        public void setObjectSchemaId(Integer objectSchemaId) { this.objectSchemaId = objectSchemaId; }
        public String getScopeIql() { return scopeIql; }
        public void setScopeIql(String scopeIql) { this.scopeIql = scopeIql; }
        public String getFilterIssueScopeIql() { return filterIssueScopeIql; }
        public void setFilterIssueScopeIql(String filterIssueScopeIql) { this.filterIssueScopeIql = filterIssueScopeIql; }
        public boolean isMultiple() { return multiple; }
        public void setMultiple(boolean multiple) { this.multiple = multiple; }
        public List<Integer> getObjectTypeIds() { return objectTypeIds; }
        public void setObjectTypeIds(List<Integer> objectTypeIds) { this.objectTypeIds = objectTypeIds; }

        @Override
        public FakeConnectBean clone() {
            try {
                return (FakeConnectBean) super.clone();
            } catch (CloneNotSupportedException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }
}
