package ru.rasulov.customfields.impl.filefield;

import com.atlassian.jira.issue.customfields.manager.GenericConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.api.dto.field.FileFieldSettings;
import ru.rasulov.customfields.impl.validation.FieldFailureException;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FileFieldConfigurationTest {
    private final GenericConfigManager genericConfig = mock(GenericConfigManager.class);
    private final FileFieldConfiguration configuration = new FileFieldConfiguration(genericConfig);

    @Test
    public void storesExtensionsWithLeadingDotAndSizeInDecimalBytes() throws Exception {
        configuration.configure(10509L, new FileFieldSettings(Arrays.asList("pdf", " .DOCX "), 3, 5, null, true));

        org.mockito.ArgumentCaptor<Object> json = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(genericConfig).create(eq("apwide-file-field-config"), eq("10509"), json.capture());
        JsonNode stored = new ObjectMapper().readTree((String) json.getValue());
        assertEquals(".pdf,.DOCX", stored.get("allowedFileExtensions").textValue());
        assertEquals(5000000L, stored.get("maxFileSize").longValue());
        assertEquals(3, stored.get("maxFiles").intValue());
        assertTrue(stored.get("autoClean").booleanValue());
    }

    @Test
    public void omittedLimitsKeepPluginDefaults() throws Exception {
        String json = configuration.toJson(new FileFieldSettings(null, null, null, null, null));

        JsonNode stored = new ObjectMapper().readTree(json);
        assertTrue(stored.get("allowedFileExtensions").isNull());
        assertTrue(stored.get("maxFileSize").isNull());
        assertEquals(10, stored.get("maxFiles").intValue());
    }

    @Test
    public void existingRowIsReplacedNotDuplicated() {
        when(genericConfig.retrieve("apwide-file-field-config", "10509")).thenReturn("{}");

        configuration.configure(10509L, new FileFieldSettings(null, 2, null, null, null));

        verify(genericConfig).update(eq("apwide-file-field-config"), eq("10509"), anyString());
        verify(genericConfig, never()).create(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void rejectsDuplicateOrMalformedExtensionsAndWrongType() {
        expectInvalid(FileFieldConfiguration.TYPE_KEY, new FileFieldSettings(Arrays.asList("pdf", ".PDF"), null, null, null, null));
        expectInvalid(FileFieldConfiguration.TYPE_KEY, new FileFieldSettings(Collections.singletonList("p d f"), null, null, null, null));
        expectInvalid(FileFieldConfiguration.TYPE_KEY, new FileFieldSettings(null, 0, null, null, null));
        expectInvalid("com.atlassian.jira.plugin.system.customfieldtypes:textfield",
                new FileFieldSettings(null, null, null, null, null));
    }

    private void expectInvalid(String typeKey, FileFieldSettings settings) {
        try {
            configuration.validate(typeKey, settings);
            fail("expected validation failure");
        } catch (FieldFailureException expected) {
            // VALIDATION_ERROR with the reason in the message
        }
    }
}
