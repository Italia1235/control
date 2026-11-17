package ru.rasulov.customfields.api.dto.requirement;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * Input of {@code set_field_required}: a preview takes {@code project} and {@code rows}; every row sets its own
 * required flag for its own fields and issue types, and all rows form one plan. An apply takes only
 * {@code dryRun=false} and {@code planId}.
 */
public final class SetFieldRequiredRequest {
    private final Boolean dryRun;
    private final String project;
    private final List<Row> rows;
    private final String planId;

    public SetFieldRequiredRequest(boolean dryRun, String project, List<Row> rows) {
        this(dryRun, project, rows, null);
    }

    public static SetFieldRequiredRequest apply(String planId) {
        return new SetFieldRequiredRequest(false, null, null, planId);
    }

    @JsonCreator
    public SetFieldRequiredRequest(@JsonProperty("dryRun") Boolean dryRun,
                                   @JsonProperty("project") String project,
                                   @JsonProperty("rows") List<Row> rows,
                                   @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.project = project;
        this.rows = Immutables.listOf(rows);
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("project") public String getProject() { return project; }
    @JsonProperty("rows") public List<Row> getRows() { return rows; }
    @JsonProperty("planId") public String getPlanId() { return planId; }

    /** One row: these fields get this required flag in these issue types. {@code required} is mandatory per row. */
    public static final class Row {
        private final List<String> fields;
        private final Boolean required;
        private final Boolean allIssueTypes;
        private final List<String> issueTypes;

        @JsonCreator
        public Row(@JsonProperty("fields") List<String> fields,
                   @JsonProperty("required") Boolean required,
                   @JsonProperty("allIssueTypes") Boolean allIssueTypes,
                   @JsonProperty("issueTypes") List<String> issueTypes) {
            this.fields = Immutables.listOf(fields);
            this.required = required;
            this.allIssueTypes = allIssueTypes;
            this.issueTypes = Immutables.listOf(issueTypes);
        }

        @JsonProperty("fields") public List<String> getFields() { return fields; }
        @JsonProperty("required") public Boolean getRequired() { return required; }
        @JsonProperty("allIssueTypes") public Boolean isAllIssueTypes() { return allIssueTypes; }
        @JsonProperty("issueTypes") public List<String> getIssueTypes() { return issueTypes; }
    }
}
