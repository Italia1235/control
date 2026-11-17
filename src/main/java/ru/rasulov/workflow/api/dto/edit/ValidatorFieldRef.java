package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A Jira field of a required-fields validator: the id is what the validator stores, the name is for people only. */
public final class ValidatorFieldRef {
    private final String id;
    private final String name;

    public ValidatorFieldRef(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @JsonProperty("id") public String getId() { return id; }
    /** {@code null} when Jira has no field with this id (a stored validator can name a deleted field). */
    @JsonProperty("name") public String getName() { return name; }
}
