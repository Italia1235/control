package ru.rasulov.common.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** A project in the {@code list_projects} answer. Issue types are expanded only for an unambiguous project match. */
public final class ProjectResponse {

    private final String key;
    private final String name;
    private final boolean issueTypesIncluded;
    private final int issueTypesTotal;
    private final List<String> issueTypes;

    public ProjectResponse(String key, String name, boolean issueTypesIncluded, int issueTypesTotal,
                           List<String> issueTypes) {
        this.key = key;
        this.name = name;
        this.issueTypesIncluded = issueTypesIncluded;
        this.issueTypesTotal = issueTypesTotal;
        this.issueTypes = Immutables.listOf(issueTypes);
    }

    @JsonProperty("key")
    public String getKey() {
        return key;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /** False when several projects matched; narrow the query to one project before inspecting its issue types. */
    @JsonProperty("issueTypesIncluded")
    public boolean isIssueTypesIncluded() {
        return issueTypesIncluded;
    }

    @JsonProperty("issueTypesTotal")
    public int getIssueTypesTotal() {
        return issueTypesTotal;
    }

    @JsonProperty("issueTypes")
    public List<String> getIssueTypes() {
        return issueTypes;
    }
}
