package ru.rasulov.workflow.api.dto.assign;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What happens to the workflow scheme: the action and the scheme it concerns. */
public final class SchemeChange {

    private final SchemeAction action;
    private final Long id;
    private final String name;

    public SchemeChange(SchemeAction action, Long id, String name) {
        this.action = action;
        this.id = id;
        this.name = name;
    }

    @JsonProperty("action")
    public SchemeAction getAction() {
        return action;
    }

    /** The scheme that is changed or created; {@code null} for a scheme a dry run would create. */
    @JsonProperty("id")
    public Long getId() {
        return id;
    }

    /** The scheme that is changed, or the name the new scheme has (or would have). */
    @JsonProperty("name")
    public String getName() {
        return name;
    }
}
