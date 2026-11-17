package ru.rasulov.customfields.impl.grid;

import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import ru.rasulov.common.api.dto.field.GridColumnDefinition;
import ru.rasulov.common.api.dto.field.GridFieldSettings;
import ru.rasulov.customfields.impl.validation.FieldFailureException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class GridFieldConfigurationTest {

    @Test
    public void listColumnStoresOptionsAndTheMatchingDefault() {
        ObjectNode columns = GridFieldConfiguration.columns(grid(Collections.<String>emptyList(),
                column("color", "list", " Green ", Arrays.asList("Red", "Green"))));

        ObjectNode color = (ObjectNode) columns.get("color");
        assertEquals("Green", color.get("defaultvalue").textValue());
        assertEquals("Red", color.get("staticOptions").get(0).get("label").textValue());
        assertEquals("label", color.get("attributes").get(0).get("key").textValue());
    }

    @Test
    public void sequenceIsAlwaysReadOnlyAndNumberDefaultsAreNormalized() {
        ObjectNode columns = GridFieldConfiguration.columns(grid(Collections.<String>emptyList(),
                column("seq", "sequence", null, null), column("qty", "number", " 1.50 ", null)));

        assertFalse(columns.get("seq").get("editable").booleanValue());
        assertEquals("1.50", columns.get("qty").get("defaultvalue").textValue());
        assertEquals(2, columns.get("qty").get("mantissa").intValue());
    }

    @Test
    public void rejectsWhatThePluginWouldStoreSilentlyOrMisread() {
        expectInvalid(grid(Collections.<String>emptyList(), column("a", "string", null, null), column("A", "string", null, null)), "more than once");
        expectInvalid(grid(Collections.singletonList("missing"), column("a", "string", null, null)), "not a column id");
        expectInvalid(grid(Collections.<String>emptyList(), column("c", "list", "Blue", Arrays.asList("Red"))), "not one of its options");
        expectInvalid(grid(Collections.<String>emptyList(), column("c", "list", null, Collections.<String>emptyList())), "options");
        expectInvalid(grid(Collections.<String>emptyList(), column("c", "list", null, Arrays.asList("Red", "red"))), "more than once");
        expectInvalid(grid(Collections.<String>emptyList(), column("s", "string", null, Arrays.asList("Red"))), "only for list");
        expectInvalid(grid(Collections.<String>emptyList(), column("n", "integer", "1.5", null)), "integer");
        expectInvalid(grid(Collections.<String>emptyList(), column("d", "date", "2026-01-01", null)), "not supported");
        expectInvalid(grid(Collections.<String>emptyList(), column("f", "formula", null, null)), "unsupported type");
        expectInvalid(grid(Collections.<String>emptyList(), column("1x", "string", null, null)), "must start with a letter");
    }

    @Test
    public void wrongFieldTypeFailsWithoutTouchingThePlugin() {
        PluginAccessor plugins = mock(PluginAccessor.class);
        try {
            new GridFieldConfiguration(plugins).validate("com.atlassian.jira.plugin.system.customfieldtypes:textfield",
                    grid(Collections.<String>emptyList(), column("a", "string", null, null)));
            fail("grid on a non-grid type must be rejected");
        } catch (FieldFailureException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("only for the Table Grid"));
        }
        verifyNoInteractions(plugins);
    }

    private static void expectInvalid(GridFieldSettings settings, String fragment) {
        try {
            GridFieldConfiguration.columns(settings);
            fail("expected validation failure containing '" + fragment + "'");
        } catch (FieldFailureException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(fragment));
        }
    }

    private static GridFieldSettings grid(List<String> searchable, GridColumnDefinition... columns) {
        return new GridFieldSettings(Arrays.asList(columns), searchable);
    }

    private static GridColumnDefinition column(String id, String type, String defaultValue, List<String> options) {
        return new GridColumnDefinition(id, "Title " + id, type, null, null, defaultValue, options, null, null);
    }
}
