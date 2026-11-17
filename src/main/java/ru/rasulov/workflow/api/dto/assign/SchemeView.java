package ru.rasulov.workflow.api.dto.assign;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** The workflow scheme of a project and who else uses it. */
public final class SchemeView {

    private final Long id;
    private final String name;
    private final boolean isDefault;
    private final boolean hasDraft;
    private final int otherProjects;
    private final List<String> otherProjectNames;

    public SchemeView(Long id, String name, boolean isDefault, boolean hasDraft, int otherProjects,
                      List<String> otherProjectNames) {
        this.id = id;
        this.name = name;
        this.isDefault = isDefault;
        this.hasDraft = hasDraft;
        this.otherProjects = otherProjects;
        this.otherProjectNames = Immutables.listOf(otherProjectNames);
    }

    /** {@code null} for the system default scheme, which has no id. */
    @JsonProperty("id")
    public Long getId() {
        return id;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /** The system default scheme: it cannot be changed, a project that uses it needs a scheme of its own. */
    @JsonProperty("isDefault")
    public boolean isDefault() {
        return isDefault;
    }

    @JsonProperty("hasDraft")
    public boolean hasDraft() {
        return hasDraft;
    }

    /** How many other projects use the scheme. */
    @JsonProperty("otherProjects")
    public int getOtherProjects() {
        return otherProjects;
    }

    /** Up to 10 of them. */
    @JsonProperty("otherProjectNames")
    public List<String> getOtherProjectNames() {
        return otherProjectNames;
    }
}
