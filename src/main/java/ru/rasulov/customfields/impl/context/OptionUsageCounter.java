package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.user.ApplicationUser;

import java.util.Map;

/** Counts the issues that use options of a custom field, for the preview only. */
public interface OptionUsageCounter {
    /**
     * @param user    the administrator; only issues this user may see are counted
     * @param options option id to the id of its parent (null for a top-level option), in the order to count
     * @return one entry per requested option id: a count or {@link OptionUsage#unknown}. A failed or too slow search is
     * an unknown entry, never an exception
     */
    Map<Long, OptionUsage> count(ApplicationUser user, CustomField field, Map<Long, Long> options);
}
