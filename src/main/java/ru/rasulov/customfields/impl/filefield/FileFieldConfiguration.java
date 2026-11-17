package ru.rasulov.customfields.impl.filefield;

import com.atlassian.jira.issue.customfields.manager.GenericConfigManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FileFieldSettings;
import ru.rasulov.customfields.impl.validation.FieldFailureException;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apwide File Field 4.x per-context settings. The app keeps them as a JSON string in Jira's generic configuration
 * ({@code apwide-file-field-config}, key = FieldConfig id) and reads them with its own defaults, so no Apwide class
 * is linked here.
 */
@Named
public class FileFieldConfiguration {
    public static final String TYPE_KEY = "com.apwide.document.file-field:apwide-doc-field";
    static final String DATA_TYPE = "apwide-file-field-config";
    static final int MAX_EXTENSIONS = 100;
    static final int MAX_FILES = 1000;
    static final int MAX_FILE_SIZE_MB = 100000;
    private static final int DEFAULT_MAX_FILES = 10;

    private final GenericConfigManager genericConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public FileFieldConfiguration(@ComponentImport GenericConfigManager genericConfig) {
        this.genericConfig = genericConfig;
    }

    public void validate(String typeKey, FileFieldSettings settings) {
        if (settings == null) return;
        if (!TYPE_KEY.equals(typeKey))
            throw invalid("'fileField' is supported only for the Apwide File Field type (" + TYPE_KEY + ").");
        extensions(settings);
        if (settings.getMaxFiles() != null && (settings.getMaxFiles() < 1 || settings.getMaxFiles() > MAX_FILES))
            throw invalid("'fileField.maxFiles' must be between 1 and " + MAX_FILES + ".");
        if (settings.getMaxFileSizeMb() != null
                && (settings.getMaxFileSizeMb() < 1 || settings.getMaxFileSizeMb() > MAX_FILE_SIZE_MB))
            throw invalid("'fileField.maxFileSizeMb' must be between 1 and " + MAX_FILE_SIZE_MB + ".");
    }

    /** Text for the preview and result: the settings exactly as they will be stored. */
    public String describe(FileFieldSettings settings) {
        List<String> extensions = extensions(settings);
        return "File Field settings for every new context: allowedFileExtensions="
                + (extensions.isEmpty() ? "any" : String.join(",", extensions))
                + ", maxFiles=" + maxFiles(settings)
                + ", maxFileSizeMb=" + (settings.getMaxFileSizeMb() == null ? "Jira upload limit" : settings.getMaxFileSizeMb())
                + ", readonly=" + Boolean.TRUE.equals(settings.getReadonly())
                + ", autoClean=" + Boolean.TRUE.equals(settings.getAutoClean()) + ".";
    }

    /** Writes the complete settings of one new context; called after {@link #validate}. */
    public void configure(Long configId, FileFieldSettings settings) {
        String key = configId.toString();
        String json = toJson(settings);
        if (genericConfig.retrieve(DATA_TYPE, key) == null) genericConfig.create(DATA_TYPE, key, json);
        else genericConfig.update(DATA_TYPE, key, json);
    }

    /** Whether Jira stores Apwide settings for this exact FieldConfig; does not change them. */
    public boolean initialized(Long configId) {
        return genericConfig.retrieve(DATA_TYPE, configId.toString()) != null;
    }

    String toJson(FileFieldSettings settings) {
        List<String> extensions = extensions(settings);
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("allowedFileExtensions", extensions.isEmpty() ? null : String.join(",", extensions));
        config.put("maxFileSize", settings.getMaxFileSizeMb() == null ? null : settings.getMaxFileSizeMb() * 1000000L);
        config.put("maxFiles", maxFiles(settings));
        config.put("autoClean", Boolean.TRUE.equals(settings.getAutoClean()));
        config.put("readonly", Boolean.TRUE.equals(settings.getReadonly()));
        config.put("displayMode", "grid");
        config.put("iconSize", 40);
        config.put("fileNameWidth", 200);
        try {
            return mapper.writeValueAsString(config);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize File Field settings", e);
        }
    }

    private static int maxFiles(FileFieldSettings settings) {
        return settings.getMaxFiles() == null ? DEFAULT_MAX_FILES : settings.getMaxFiles();
    }

    /** Apwide silently drops entries without a leading dot, so "pdf" is stored as ".pdf". */
    private static List<String> extensions(FileFieldSettings settings) {
        if (settings.getAllowedFileExtensions().size() > MAX_EXTENSIONS)
            throw invalid("'fileField.allowedFileExtensions' accepts at most " + MAX_EXTENSIONS + " extensions.");
        List<String> result = new ArrayList<String>(settings.getAllowedFileExtensions().size());
        Set<String> seen = new HashSet<String>();
        for (String raw : settings.getAllowedFileExtensions()) {
            String value = raw == null ? "" : raw.trim();
            String extension = value.startsWith(".") ? value : "." + value;
            if (extension.length() < 2 || extension.length() > 32
                    || !extension.substring(1).matches("[\\p{L}\\p{N}_.+-]+"))
                throw invalid("'fileField.allowedFileExtensions' contains an invalid extension '" + raw
                        + "'. Use values like \"pdf\" or \".docx\".");
            if (!seen.add(extension.toLowerCase(Locale.ROOT)))
                throw invalid("'fileField.allowedFileExtensions' lists '" + extension + "' more than once.");
            result.add(extension);
        }
        return result;
    }

    private static FieldFailureException invalid(String message) {
        return new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR, message);
    }
}
