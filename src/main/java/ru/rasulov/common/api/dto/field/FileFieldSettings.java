package ru.rasulov.common.api.dto.field;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Apwide File Field configuration shared by every newly created context of a field. */
public final class FileFieldSettings {

    private final List<String> allowedFileExtensions;
    private final Integer maxFiles;
    private final Integer maxFileSizeMb;
    private final Boolean readonly;
    private final Boolean autoClean;

    @JsonCreator
    public FileFieldSettings(@JsonProperty("allowedFileExtensions") List<String> allowedFileExtensions,
                             @JsonProperty("maxFiles") Integer maxFiles,
                             @JsonProperty("maxFileSizeMb") Integer maxFileSizeMb,
                             @JsonProperty("readonly") Boolean readonly,
                             @JsonProperty("autoClean") Boolean autoClean) {
        this.allowedFileExtensions = Immutables.listOf(allowedFileExtensions);
        this.maxFiles = maxFiles;
        this.maxFileSizeMb = maxFileSizeMb;
        this.readonly = readonly;
        this.autoClean = autoClean;
    }

    /** Empty means every extension is allowed. */
    @JsonProperty("allowedFileExtensions")
    public List<String> getAllowedFileExtensions() {
        return allowedFileExtensions;
    }

    @JsonProperty("maxFiles")
    public Integer getMaxFiles() {
        return maxFiles;
    }

    /** Decimal megabytes, as in the Apwide configuration UI; {@code null} falls back to Jira's upload limit. */
    @JsonProperty("maxFileSizeMb")
    public Integer getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    @JsonProperty("readonly")
    public Boolean getReadonly() {
        return readonly;
    }

    @JsonProperty("autoClean")
    public Boolean getAutoClean() {
        return autoClean;
    }
}
