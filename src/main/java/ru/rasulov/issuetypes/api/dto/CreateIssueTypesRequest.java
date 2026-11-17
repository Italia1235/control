package ru.rasulov.issuetypes.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;

import java.util.List;

/**
 * Preview lists the global issue types of one batch; apply carries only {@code dryRun=false} and the confirmed plan id.
 * One type is a batch of one.
 */
public final class CreateIssueTypesRequest {
    private final Boolean dryRun;
    private final List<IssueTypeDefinition> issueTypes;
    private final String planId;

    @JsonCreator
    public CreateIssueTypesRequest(@JsonProperty("dryRun") Boolean dryRun,
                                   @JsonProperty("issueTypes") List<IssueTypeDefinition> issueTypes,
                                   @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.issueTypes = Immutables.listOf(issueTypes);
        this.planId = planId;
    }

    @JsonProperty("dryRun")
    public Boolean isDryRun() { return dryRun; }
    @JsonProperty("issueTypes")
    public List<IssueTypeDefinition> getIssueTypes() { return issueTypes; }
    @JsonProperty("planId")
    public String getPlanId() { return planId; }
}
