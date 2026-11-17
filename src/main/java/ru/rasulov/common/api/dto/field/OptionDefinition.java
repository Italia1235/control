package ru.rasulov.common.api.dto.field;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import ru.rasulov.common.api.dto.Immutables;

/** One option of a select-like field; {@code children} only for cascading select (one level). */
public final class OptionDefinition {

    private final String value;
    private final List<OptionDefinition> children;

    @JsonCreator
    public OptionDefinition(@JsonProperty("value") String value,
                            @JsonProperty("children") List<OptionDefinition> children) {
        this.value = value;
        this.children = Immutables.listOf(children);
    }

    /** Text shown in Jira. */
    @JsonProperty("value")
    public String getValue() {
        return value;
    }

    /** Child options of a cascading select; empty for every other type. */
    @JsonProperty("children")
    public List<OptionDefinition> getChildren() {
        return children;
    }
}
