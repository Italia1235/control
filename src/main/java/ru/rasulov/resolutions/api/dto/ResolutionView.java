package ru.rasulov.resolutions.api.dto;

import com.atlassian.jira.issue.resolution.Resolution;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ResolutionView {
    private final String id;
    private final String name;
    private final String description;
    public ResolutionView(String id, String name, String description) {
        this.id = id; this.name = name; this.description = description;
    }
    public static ResolutionView of(Resolution resolution) {
        return new ResolutionView(resolution.getId(), resolution.getName(), resolution.getDescription());
    }
    @JsonProperty("id") public String getId() { return id; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
}
