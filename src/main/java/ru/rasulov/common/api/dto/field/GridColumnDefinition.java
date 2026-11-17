package ru.rasulov.common.api.dto.field;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** One column of a Table Grid Next Generation table. */
public final class GridColumnDefinition {

    private final String id;
    private final String title;
    private final String type;
    private final Boolean required;
    private final Boolean editable;
    private final String defaultValue;
    private final List<String> options;
    private final Integer width;
    private final Integer decimals;

    @JsonCreator
    public GridColumnDefinition(@JsonProperty("id") String id,
                                @JsonProperty("title") String title,
                                @JsonProperty("type") String type,
                                @JsonProperty("required") Boolean required,
                                @JsonProperty("editable") Boolean editable,
                                @JsonProperty("defaultValue") String defaultValue,
                                @JsonProperty("options") List<String> options,
                                @JsonProperty("width") Integer width,
                                @JsonProperty("decimals") Integer decimals) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.required = required;
        this.editable = editable;
        this.defaultValue = defaultValue;
        this.options = Immutables.listOf(options);
        this.width = width;
        this.decimals = decimals;
    }

    @JsonProperty("id")
    public String getId() {
        return id;
    }

    @JsonProperty("title")
    public String getTitle() {
        return title;
    }

    @JsonProperty("type")
    public String getType() {
        return type;
    }

    @JsonProperty("required")
    public Boolean getRequired() {
        return required;
    }

    @JsonProperty("editable")
    public Boolean getEditable() {
        return editable;
    }

    @JsonProperty("defaultValue")
    public String getDefaultValue() {
        return defaultValue;
    }

    /** Values of a list/multiselectlist column, in display order. */
    @JsonProperty("options")
    public List<String> getOptions() {
        return options;
    }

    @JsonProperty("width")
    public Integer getWidth() {
        return width;
    }

    /** Digits after the decimal point of a number column. */
    @JsonProperty("decimals")
    public Integer getDecimals() {
        return decimals;
    }
}
