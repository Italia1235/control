package ru.rasulov.customfields.impl.requirement;

import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredRequest;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredRequest.Row;

import java.util.Collections;
import java.util.List;

/** Builds {@code set_field_required} requests: an ordinary single-scope request is one row. */
public final class RequirementRequests {
    private RequirementRequests() { }

    /** One row; {@code required} defaults to true as in the old tests, scope is allIssueTypes or issueTypes. */
    public static SetFieldRequiredRequest oneRow(boolean dryRun, String project, List<String> fields,
                                                 Boolean allIssueTypes, List<String> issueTypes) {
        return oneRow(dryRun, project, fields, true, allIssueTypes, issueTypes);
    }

    public static SetFieldRequiredRequest oneRow(boolean dryRun, String project, List<String> fields, Boolean required,
                                                 Boolean allIssueTypes, List<String> issueTypes) {
        return new SetFieldRequiredRequest(dryRun, project,
                Collections.singletonList(new Row(fields, required, allIssueTypes, issueTypes)));
    }

    public static SetFieldRequiredRequest apply(String planId) {
        return SetFieldRequiredRequest.apply(planId);
    }
}
