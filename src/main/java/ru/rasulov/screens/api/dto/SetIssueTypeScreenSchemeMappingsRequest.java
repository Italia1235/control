package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * Preview input of {@code set_issue_type_screen_scheme_mappings}: one existing Issue Type Screen Scheme and rows
 * {@code action + issue type + Screen Scheme}. Apply carries only {@code dryRun=false} and {@code planId}.
 * Action and references stay plain text so that a bad value is answered with a row-numbered error, not a parser error.
 */
public final class SetIssueTypeScreenSchemeMappingsRequest {
    private final Boolean dryRun;
    private final String issueTypeScreenScheme;
    private final List<Row> rows;
    private final String planId;

    @JsonCreator
    public SetIssueTypeScreenSchemeMappingsRequest(@JsonProperty("dryRun") Boolean dryRun,
                                                   @JsonProperty("issueTypeScreenScheme") String issueTypeScreenScheme,
                                                   @JsonProperty("rows") List<Row> rows,
                                                   @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.issueTypeScreenScheme = issueTypeScreenScheme;
        this.rows = rows == null ? null : Immutables.listOf(rows);
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    /** Positive id or exact name (case-insensitive) of an existing Issue Type Screen Scheme. */
    @JsonProperty("issueTypeScreenScheme") public String getIssueTypeScreenScheme() { return issueTypeScreenScheme; }
    @JsonProperty("rows") public List<Row> getRows() { return rows; }
    @JsonProperty("planId") public String getPlanId() { return planId; }

    /** One row: ADD / REPLACE / REMOVE of the mapping of an issue type (id, exact name or {@code DEFAULT}). */
    public static final class Row {
        private final String action;
        private final String issueType;
        private final String screenScheme;

        @JsonCreator
        public Row(@JsonProperty("action") String action, @JsonProperty("issueType") String issueType,
                   @JsonProperty("screenScheme") String screenScheme) {
            this.action = action; this.issueType = issueType; this.screenScheme = screenScheme;
        }

        @JsonProperty("action") public String getAction() { return action; }
        @JsonProperty("issueType") public String getIssueType() { return issueType; }
        /** Id or exact name of an existing Screen Scheme; required for ADD and REPLACE, forbidden for REMOVE. */
        @JsonProperty("screenScheme") public String getScreenScheme() { return screenScheme; }
    }
}
