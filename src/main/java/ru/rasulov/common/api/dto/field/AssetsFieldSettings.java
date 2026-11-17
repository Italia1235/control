package ru.rasulov.common.api.dto.field;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Assets object configuration shared by every newly created context of a field. */
public final class AssetsFieldSettings {

    private final Integer objectSchemaId;
    private final String filterScopeAql;

    @JsonCreator
    public AssetsFieldSettings(@JsonProperty("objectSchemaId") Integer objectSchemaId,
                               @JsonProperty("filterScopeAql") String filterScopeAql) {
        this.objectSchemaId = objectSchemaId;
        this.filterScopeAql = filterScopeAql;
    }

    @JsonProperty("objectSchemaId")
    public Integer getObjectSchemaId() {
        return objectSchemaId;
    }

    @JsonProperty("filterScopeAql")
    public String getFilterScopeAql() {
        return filterScopeAql;
    }
}
