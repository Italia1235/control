package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Counts issues by the id of the option (never by its text, which would also count equally named options of other
 * contexts) with the administrator's own issue permissions. An option id belongs to the configuration of one context,
 * so the search needs no project or issue type restriction.
 */
@Named
public class JqlOptionUsageCounter implements OptionUsageCounter {
    /** All searches of one preview together must finish within this budget; the rest is reported as unknown. */
    static final long BUDGET_MILLIS = 10_000L;

    private final SearchService search;
    private final LongSupplier nanoClock;

    @Inject
    public JqlOptionUsageCounter(@ComponentImport SearchService search) { this(search, System::nanoTime); }

    JqlOptionUsageCounter(SearchService search, LongSupplier nanoClock) { this.search = search; this.nanoClock = nanoClock; }

    @Override
    public Map<Long, OptionUsage> count(ApplicationUser user, CustomField field, Map<Long, Long> options) {
        Map<Long, OptionUsage> result = new LinkedHashMap<Long, OptionUsage>();
        String number = field.getId() != null && field.getId().matches("customfield_[1-9][0-9]*")
                ? field.getId().substring("customfield_".length()) : null;
        long deadline = nanoClock.getAsLong() + BUDGET_MILLIS * 1_000_000L;
        for (Map.Entry<Long, Long> option : options.entrySet()) {
            result.put(option.getKey(), number == null
                    ? OptionUsage.unknown("Usage can be searched only for a custom field; " + field.getId() + " is not one.")
                    : count(user, number, option.getKey(), option.getValue(), deadline));
        }
        return result;
    }

    private OptionUsage count(ApplicationUser user, String field, Long optionId, Long parentOptionId, long deadline) {
        if (nanoClock.getAsLong() - deadline >= 0) return OptionUsage.unknown(exhausted());
        String jql = parentOptionId == null ? "cf[" + field + "] = " + optionId
                : "cf[" + field + "] in cascadeOption(" + parentOptionId + ", " + optionId + ")";
        try {
            SearchService.ParseResult parsed = search.parseQuery(user, jql);
            if (!parsed.isValid()) return OptionUsage.unknown("The usage query was refused: " + parsed.getErrors().getErrorMessages());
            long issues = search.searchCount(user, parsed.getQuery());
            return nanoClock.getAsLong() - deadline >= 0 ? OptionUsage.unknown(exhausted()) : OptionUsage.count(issues);
        } catch (SearchException | RuntimeException e) {
            return OptionUsage.unknown("The usage search failed: " + e.getMessage());
        }
    }

    private static String exhausted() {
        return "The time budget of " + BUDGET_MILLIS / 1000 + " seconds for counting usage is used up; usage was not counted.";
    }
}
