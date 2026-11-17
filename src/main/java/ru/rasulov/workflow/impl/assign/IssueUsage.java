package ru.rasulov.workflow.impl.assign;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** How many issues of a project stand in which status, by issue type. Ids, not names: that is what the database holds. */
final class IssueUsage {

    private final Map<String, Map<String, Integer>> byType = new LinkedHashMap<String, Map<String, Integer>>();
    private Supplier<IssueUsage> loader;

    IssueUsage() {
    }

    /**
     * Usage that is counted when something first reads it, so a plan that never looks at the issues does not pay for
     * scanning them. The count is made at most once and only by the first read.
     */
    static IssueUsage lazy(Supplier<IssueUsage> loader) {
        IssueUsage usage = new IssueUsage();
        usage.loader = loader;
        return usage;
    }

    void add(String issueTypeId, String statusId) {
        Map<String, Integer> statuses = byType.get(issueTypeId);
        if (statuses == null) {
            statuses = new LinkedHashMap<String, Integer>();
            byType.put(issueTypeId, statuses);
        }
        Integer count = statuses.get(statusId);
        statuses.put(statusId, count == null ? 1 : count + 1);
    }

    /** Issue types that have at least one issue. */
    Set<String> issueTypeIds() {
        return Collections.unmodifiableSet(counted().byType.keySet());
    }

    /** Status id to number of issues; empty when the type has no issues. */
    Map<String, Integer> statuses(String issueTypeId) {
        Map<String, Integer> statuses = counted().byType.get(issueTypeId);
        return statuses == null ? Collections.<String, Integer>emptyMap() : Collections.unmodifiableMap(statuses);
    }

    int issues(String issueTypeId) {
        int total = 0;
        for (int count : statuses(issueTypeId).values()) {
            total += count;
        }
        return total;
    }

    private IssueUsage counted() {
        if (loader == null) {
            return this;
        }
        IssueUsage loaded = loader.get();
        loader = null;
        for (Map.Entry<String, Map<String, Integer>> type : loaded.byType.entrySet()) {
            byType.put(type.getKey(), type.getValue());
        }
        return this;
    }
}
