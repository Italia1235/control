package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import ru.rasulov.customfields.impl.context.ResolvedContext;

import java.util.List;

/**
 * A {@code FieldDefinition} whose type, searcher, projects and issue types are resolved to Jira objects and whose
 * options are checked. The options live in the contexts: each context carries the list written into it.
 */
public final class ResolvedFieldDefinition {

    private final String name;
    private final String description;
    private final CustomFieldType<?, ?> type;
    private final CustomFieldSearcher searcher;
    private final boolean searcherRequested;
    private final List<ResolvedContext> contexts;

    ResolvedFieldDefinition(String name, String description, CustomFieldType<?, ?> type, CustomFieldSearcher searcher,
                            boolean searcherRequested, List<ResolvedContext> contexts) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.searcher = searcher;
        this.searcherRequested = searcherRequested;
        this.contexts = contexts;
    }

    /** Trimmed, non-empty. */
    public String getName() {
        return name;
    }

    /** Never {@code null}. */
    public String getDescription() {
        return description;
    }

    public CustomFieldType<?, ?> getType() {
        return type;
    }

    /** The requested searcher, else the default one; {@code null} for types that have no searcher. */
    public CustomFieldSearcher getSearcher() {
        return searcher;
    }

    /** {@code true} if the request named a searcher, {@code false} if {@link #getSearcher()} is only the default. */
    public boolean isSearcherRequested() {
        return searcherRequested;
    }

    /** At least one, unique names, no overlaps; each with the options that are written into it. */
    public List<ResolvedContext> getContexts() {
        return contexts;
    }

    /** {@code true} if any context gets options. */
    public boolean hasOptions() {
        for (ResolvedContext context : contexts) {
            if (!context.getOptions().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
