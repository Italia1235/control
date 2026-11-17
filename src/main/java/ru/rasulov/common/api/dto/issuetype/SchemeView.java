package ru.rasulov.common.api.dto.issuetype;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** The issue type scheme of a project, planned or written. */
public final class SchemeView {
    private final SchemeAction action;
    private final Long id;
    private final String name;
    private final boolean defaultScheme;
    private final int otherProjects;
    private final List<String> otherProjectNames;

    public SchemeView(SchemeAction action, Long id, String name, boolean defaultScheme, int otherProjects,
                      List<String> otherProjectNames) {
        this.action = action; this.id = id; this.name = name; this.defaultScheme = defaultScheme;
        this.otherProjects = otherProjects; this.otherProjectNames = Immutables.listOf(otherProjectNames);
    }
    @JsonProperty("action") public SchemeAction getAction() { return action; }
    @JsonProperty("id") public Long getId() { return id; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("isDefault") public boolean isDefaultScheme() { return defaultScheme; }
    @JsonProperty("otherProjects") public int getOtherProjects() { return otherProjects; }
    @JsonProperty("otherProjectNames") public List<String> getOtherProjectNames() { return otherProjectNames; }
}
