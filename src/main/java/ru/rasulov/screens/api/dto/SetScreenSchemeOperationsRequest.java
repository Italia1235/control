package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * Preview input of {@code set_screen_scheme_operations}: one existing Screen Scheme and rows
 * {@code action + operation + screen}. Apply carries only {@code dryRun=false} and {@code planId}.
 * Action, operation and references stay plain text so that a bad value is answered with a row-numbered error, not a parser error.
 */
public final class SetScreenSchemeOperationsRequest {
    private final Boolean dryRun;
    private final String screenScheme;
    private final List<Row> rows;
    private final String planId;

    @JsonCreator
    public SetScreenSchemeOperationsRequest(@JsonProperty("dryRun") Boolean dryRun,
                                            @JsonProperty("screenScheme") String screenScheme,
                                            @JsonProperty("rows") List<Row> rows,
                                            @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.screenScheme = screenScheme;
        this.rows = rows == null ? null : Immutables.listOf(rows);
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    /** Positive id or exact name (case-insensitive) of an existing Screen Scheme. */
    @JsonProperty("screenScheme") public String getScreenScheme() { return screenScheme; }
    @JsonProperty("rows") public List<Row> getRows() { return rows; }
    @JsonProperty("planId") public String getPlanId() { return planId; }

    /** One row: ADD / REPLACE / REMOVE of the screen of an operation (DEFAULT, CREATE, EDIT or VIEW). */
    public static final class Row {
        private final String action;
        private final String operation;
        private final String screen;

        @JsonCreator
        public Row(@JsonProperty("action") String action, @JsonProperty("operation") String operation,
                   @JsonProperty("screen") String screen) {
            this.action = action; this.operation = operation; this.screen = screen;
        }

        @JsonProperty("action") public String getAction() { return action; }
        @JsonProperty("operation") public String getOperation() { return operation; }
        /** Id or exact name of an existing Screen; required for ADD and REPLACE, forbidden for REMOVE. */
        @JsonProperty("screen") public String getScreen() { return screen; }
    }
}
