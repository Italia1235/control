package ru.rasulov.common.impl.lookup;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.LookupLimits;
import ru.rasulov.common.api.dto.IssueTypeListResponse;
import ru.rasulov.common.api.dto.IssueTypeResponse;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Searchable, bounded issue-type reference for callers that must not receive the whole Jira catalog. */
@Named
public class IssueTypeCatalog {

    static final int MAX_DESCRIPTION_LENGTH = 200;

    private final IssueTypeManager issueTypeManager;

    @Inject
    public IssueTypeCatalog(@ComponentImport IssueTypeManager issueTypeManager) {
        this.issueTypeManager = issueTypeManager;
    }

    public IssueTypeListResponse list(String query, Integer limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int size = limit == null || limit < 1 ? LookupLimits.DEFAULT_REFERENCE_LIST_SIZE
                : Math.min(limit, LookupLimits.MAX_REFERENCE_LIST_SIZE);
        List<IssueTypeResponse> matching = new ArrayList<IssueTypeResponse>();
        for (IssueType type : issueTypeManager.getIssueTypes()) {
            if (matches(type, needle)) {
                matching.add(new IssueTypeResponse(type.getId(), type.getName(), truncate(type.getDescription()),
                        type.isSubTask()));
            }
        }
        Collections.sort(matching, new Comparator<IssueTypeResponse>() {
            @Override
            public int compare(IssueTypeResponse a, IssueTypeResponse b) {
                int byName = a.getName().compareToIgnoreCase(b.getName());
                return byName != 0 ? byName : a.getId().compareTo(b.getId());
            }
        });
        return new IssueTypeListResponse(matching.size(),
                matching.subList(0, Math.min(size, matching.size())));
    }

    private static boolean matches(IssueType type, String needle) {
        if (needle.isEmpty()) return true;
        String name = type.getName() == null ? "" : type.getName().toLowerCase(Locale.ROOT);
        return type.getId().toLowerCase(Locale.ROOT).contains(needle) || name.contains(needle);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_DESCRIPTION_LENGTH ? text : text.substring(0, MAX_DESCRIPTION_LENGTH);
    }
}
