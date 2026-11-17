package ru.rasulov.projects.api.dto;

import com.atlassian.jira.user.ApplicationUser;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Stable, non-sensitive identity displayed for a project lead; email is intentionally excluded. */
public final class ProjectLeadView {
    private final String username;
    private final String displayName;
    private final String userKey;

    public ProjectLeadView(String username, String displayName, String userKey) {
        this.username = username;
        this.displayName = displayName;
        this.userKey = userKey;
    }

    public static ProjectLeadView of(ApplicationUser user) {
        return user == null ? null : new ProjectLeadView(user.getUsername(), user.getDisplayName(), user.getKey());
    }

    @JsonProperty("username")
    public String getUsername() {
        return username;
    }

    @JsonProperty("displayName")
    public String getDisplayName() {
        return displayName;
    }

    @JsonProperty("userKey")
    public String getUserKey() {
        return userKey;
    }
}
