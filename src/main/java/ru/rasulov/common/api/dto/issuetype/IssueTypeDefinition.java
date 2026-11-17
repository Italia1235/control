package ru.rasulov.common.api.dto.issuetype;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One wanted type. {@code key} is local to the request: it names the item in the answer and nowhere in Jira. */
public final class IssueTypeDefinition {
    private final String key;
    private final String name;
    private final String description;
    private final Boolean subtask;

    @JsonCreator
    public IssueTypeDefinition(@JsonProperty("key") String key,
                               @JsonProperty("name") String name,
                               @JsonProperty("description") String description,
                               @JsonProperty("subtask") Boolean subtask) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.subtask = subtask;
    }

    @JsonProperty("key")
    public String getKey() { return key; }
    @JsonProperty("name")
    public String getName() { return name; }
    @JsonProperty("description")
    public String getDescription() { return description; }
    @JsonProperty("subtask")
    public Boolean isSubtask() { return subtask; }
}
