package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch preview input of {@code set_project_operation_screens}: one project and rows {@code scope -> partial patch}.
 * Every row names its scope by {@code issueTypes} (current project types) or {@code allIssueTypes:true} (the DEFAULT
 * mapping and every explicit mapping of the project's Issue Type Screen Scheme), never both. Apply carries only
 * {@code dryRun=false} and {@code planId}.
 */
public final class SetProjectOperationScreensRequest {
    private final Boolean dryRun;
    private final String project;
    private final List<Mapping> mappings;
    private final String issueTypeScreenSchemeName;
    private final String planId;

    @JsonCreator
    public SetProjectOperationScreensRequest(@JsonProperty("dryRun") Boolean dryRun,
                                             @JsonProperty("project") String project,
                                             @JsonProperty("mappings") List<Mapping> mappings,
                                             @JsonProperty("issueTypeScreenSchemeName") String issueTypeScreenSchemeName,
                                             @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.project = project;
        this.mappings = mappings == null ? null : Immutables.listOf(mappings);
        this.issueTypeScreenSchemeName = issueTypeScreenSchemeName;
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("project") public String getProject() { return project; }
    @JsonProperty("mappings") public List<Mapping> getMappings() { return mappings; }
    /** Optional exact name for the new Issue Type Screen Scheme; null keeps the generated name. */
    @JsonProperty("issueTypeScreenSchemeName") public String getIssueTypeScreenSchemeName() { return issueTypeScreenSchemeName; }
    @JsonProperty("planId") public String getPlanId() { return planId; }

    /** One row: the issue types it covers and a partial CREATE/EDIT/VIEW patch (operation to exact Screen name or id). */
    public static final class Mapping {
        private final List<String> issueTypes;
        private final Boolean allIssueTypes;
        private final Map<String, String> screens;
        private final String screenSchemeName;

        @JsonCreator
        public Mapping(@JsonProperty("issueTypes") List<String> issueTypes,
                       @JsonProperty("allIssueTypes") Boolean allIssueTypes,
                       @JsonProperty("screens") Map<String, String> screens,
                       @JsonProperty("screenSchemeName") String screenSchemeName) {
            this.screenSchemeName = screenSchemeName;
            this.issueTypes = issueTypes == null ? null : Immutables.listOf(issueTypes);
            this.allIssueTypes = allIssueTypes;
            this.screens = screens == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(screens));
        }

        @JsonProperty("issueTypes") public List<String> getIssueTypes() { return issueTypes; }
        @JsonProperty("allIssueTypes") public Boolean getAllIssueTypes() { return allIssueTypes; }
        @JsonProperty("screens") public Map<String, String> getScreens() { return screens; }
        /** Optional exact name for the new Screen Scheme this row needs; null keeps the generated name. */
        @JsonProperty("screenSchemeName") public String getScreenSchemeName() { return screenSchemeName; }
    }
}
