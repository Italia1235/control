package ru.rasulov.common.api.dto.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One Jira object a create tool made or reused, with the admin page where the user can find (and delete) it. */
public final class ChangedObject {

    private final ChangeKind kind;
    private final String id;
    private final String name;
    private final String url;
    private final Integer count;

    public ChangedObject(ChangeKind kind, String id, String name, String url, Integer count) {
        this.kind = kind;
        this.id = id;
        this.name = name;
        this.url = url;
        this.count = count;
    }

    @JsonProperty("kind")
    public ChangeKind getKind() { return kind; }

    /** Jira's id; {@code null} when Jira has none for the object (a workflow is identified by its name). */
    @JsonProperty("id")
    public String getId() { return id; }

    @JsonProperty("name")
    public String getName() { return name; }

    /** Absolute admin page, or {@code null} when Jira has no reliable page for this kind. */
    @JsonProperty("url")
    public String getUrl() { return url; }

    /** Set only for {@code OPTION}: how many options were created (not listed one by one). */
    @JsonProperty("count")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer getCount() { return count; }
}
