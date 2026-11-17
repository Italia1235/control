package ru.rasulov.common.api.dto.field;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Table Grid Next Generation table layout shared by every newly created context of a field. */
public final class GridFieldSettings {

    private final List<GridColumnDefinition> columns;
    private final List<String> searchableColumns;

    @JsonCreator
    public GridFieldSettings(@JsonProperty("columns") List<GridColumnDefinition> columns,
                             @JsonProperty("searchableColumns") List<String> searchableColumns) {
        this.columns = Immutables.listOf(columns);
        this.searchableColumns = Immutables.listOf(searchableColumns);
    }

    /** Columns in display order. */
    @JsonProperty("columns")
    public List<GridColumnDefinition> getColumns() {
        return columns;
    }

    /** Ids of columns indexed for JQL search. */
    @JsonProperty("searchableColumns")
    public List<String> getSearchableColumns() {
        return searchableColumns;
    }
}
