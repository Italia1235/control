package ru.rasulov.common.api.dto.field;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * One custom field to create.
 * <p>
 * Getters carry explicit {@code @JsonProperty}: Jira's REST Jackson mapper does not auto-detect {@code getX()}
 * methods, so unannotated getters are silently dropped from the JSON.
 */
public final class FieldDefinition {

    private final String name;
    private final String description;
    private final String typeKey;
    private final String searcherKey;
    private final List<OptionDefinition> options;
    private final List<ContextDefinition> contexts;
    private final AssetsFieldSettings assets;
    private final FileFieldSettings fileField;
    private final GridFieldSettings grid;

    /** A field without Apwide File Field or Table Grid settings. */
    public FieldDefinition(String name, String description, String typeKey, String searcherKey,
                           List<OptionDefinition> options, List<ContextDefinition> contexts,
                           AssetsFieldSettings assets) {
        this(name, description, typeKey, searcherKey, options, contexts, assets, null, null);
    }

    @JsonCreator
    public FieldDefinition(@JsonProperty("name") String name,
                           @JsonProperty("description") String description,
                           @JsonProperty("typeKey") String typeKey,
                           @JsonProperty("searcherKey") String searcherKey,
                           @JsonProperty("options") List<OptionDefinition> options,
                           @JsonProperty("contexts") List<ContextDefinition> contexts,
                           @JsonProperty("assets") AssetsFieldSettings assets,
                           @JsonProperty("fileField") FileFieldSettings fileField,
                           @JsonProperty("grid") GridFieldSettings grid) {
        this.name = name;
        this.description = description;
        this.typeKey = typeKey;
        this.searcherKey = searcherKey;
        this.options = Immutables.listOf(options);
        this.contexts = Immutables.listOf(contexts);
        this.assets = assets;
        this.fileField = fileField;
        this.grid = grid;
    }

    /** Field name as shown in Jira. */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /** Optional description. */
    @JsonProperty("description")
    public String getDescription() {
        return description;
    }

    /** Full module key ({@code pluginKey:moduleKey}) or short key of a built-in type ({@code textfield}). */
    @JsonProperty("typeKey")
    public String getTypeKey() {
        return typeKey;
    }

    /** Optional searcher key; {@code null} selects the default searcher of the type. */
    @JsonProperty("searcherKey")
    public String getSearcherKey() {
        return searcherKey;
    }

    /**
     * Options for select-like types, in display order; used only by contexts where options is omitted.
     * An explicitly empty context list does not inherit. Empty when no shared options were given.
     */
    @JsonProperty("options")
    public List<OptionDefinition> getOptions() {
        return options;
    }

    /**
     * Where the field applies. Required, at least one: a field is never given a context the caller did not name.
     * Several contexts only when they are meant to be separate (for example, different options per project).
     */
    @JsonProperty("contexts")
    public List<ContextDefinition> getContexts() {
        return contexts;
    }

    /** Optional Assets object configuration, applied identically to every newly created context. */
    @JsonProperty("assets")
    public AssetsFieldSettings getAssets() {
        return assets;
    }

    /** Optional Apwide File Field configuration, applied identically to every newly created context. */
    @JsonProperty("fileField")
    public FileFieldSettings getFileField() {
        return fileField;
    }

    /** Optional Table Grid Next Generation layout, applied identically to every newly created context. */
    @JsonProperty("grid")
    public GridFieldSettings getGrid() {
        return grid;
    }
}
