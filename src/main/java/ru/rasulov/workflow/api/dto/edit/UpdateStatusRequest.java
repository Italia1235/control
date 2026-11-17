package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Input of {@code update_status}: preview with the status id and the fields to change (an absent field is kept),
 * apply with the preview's planId only. {@code category} is TO_DO, IN_PROGRESS or DONE.
 */
public final class UpdateStatusRequest {
    private final Boolean dryRun;
    private final String statusId;
    private final String name;
    private final String description;
    private final String category;
    private final String planId;

    @JsonCreator
    public UpdateStatusRequest(@JsonProperty("dryRun") Boolean dryRun, @JsonProperty("statusId") String statusId,
                               @JsonProperty("name") String name, @JsonProperty("description") String description,
                               @JsonProperty("category") String category, @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.statusId = statusId;
        this.name = name;
        this.description = description;
        this.category = category;
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("statusId") public String getStatusId() { return statusId; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("category") public String getCategory() { return category; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
