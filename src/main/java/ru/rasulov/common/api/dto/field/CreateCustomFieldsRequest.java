package ru.rasulov.common.api.dto.field;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import ru.rasulov.common.api.dto.Immutables;

/** Input of the {@code create_custom_fields} operation. */
public final class CreateCustomFieldsRequest {

    private final Boolean dryRun;
    private final List<FieldDefinition> fields;
    private final String planId;

    public CreateCustomFieldsRequest(boolean dryRun, List<FieldDefinition> fields) {
        this(dryRun, fields, null);
    }

    @JsonCreator
    public CreateCustomFieldsRequest(@JsonProperty("dryRun") Boolean dryRun,
                                     @JsonProperty("fields") List<FieldDefinition> fields,
                                     @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.fields = Immutables.listOf(fields);
        this.planId = planId;
    }

    @JsonProperty("dryRun")
    public Boolean isDryRun() {
        return dryRun;
    }

    @JsonProperty("fields")
    public List<FieldDefinition> getFields() {
        return fields;
    }

    @JsonProperty("planId")
    public String getPlanId() { return planId; }
}
