package ru.rasulov.common.impl.lookup;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.LookupLimits;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finds the issue type a caller means: by id when the text is a number, else by name ignoring case. Sub-task types
 * are ordinary issue types here: the caller names them like any other. A name shared by several types is refused
 * with their ids, the caller passes an id then.
 */
@Named
public class IssueTypeResolver {

    private final IssueTypeManager issueTypeManager;

    @Inject
    public IssueTypeResolver(@ComponentImport IssueTypeManager issueTypeManager) {
        this.issueTypeManager = issueTypeManager;
    }

    /**
     * @throws LookupFailure blank, unknown or ambiguous issue type
     */
    public IssueType resolve(String nameOrId) {
        return session().resolve(nameOrId);
    }

    /**
     * Resolver for ONE request: the issue type catalog is read at most once, on the first name lookup, and shared by
     * every later lookup of the same session. Never keep a session beyond the request that made it.
     */
    public Session session() {
        return new Session();
    }

    public final class Session {

        private java.util.Collection<IssueType> catalog;

        /**
         * @throws LookupFailure blank, unknown or ambiguous issue type
         */
        public IssueType resolve(String nameOrId) {
            return IssueTypeResolver.this.resolve(nameOrId, this);
        }

        private java.util.Collection<IssueType> catalog() {
            if (catalog == null) {
                catalog = issueTypeManager.getIssueTypes();
            }
            return catalog;
        }
    }

    private IssueType resolve(String nameOrId, Session session) {
        String text = nameOrId == null ? "" : nameOrId.trim();
        if (text.isEmpty()) {
            throw new LookupFailure(LookupFailure.Kind.INVALID,
                    "An issue type must be given by its name or id, not blank. Use list_issue_types to find it.");
        }
        if (isNumber(text)) {
            IssueType byId = issueTypeManager.getIssueType(text);
            if (byId != null) {
                return byId;
            }
        }
        List<IssueType> byName = new ArrayList<IssueType>();
        for (IssueType candidate : session.catalog()) {
            if (text.equalsIgnoreCase(candidate.getName())) {
                byName.add(candidate);
            }
        }
        if (byName.size() == 1) {
            return byName.get(0);
        }
        if (byName.size() > 1) {
            List<String> ids = new ArrayList<String>();
            for (IssueType type : byName) {
                ids.add(type.getId());
            }
            throw new LookupFailure(LookupFailure.Kind.INVALID,
                    "Several issue types are named '" + text + "'. Pass the id of the one meant (see suggestions).",
                    ids);
        }
        throw new LookupFailure(LookupFailure.Kind.UNKNOWN_ISSUE_TYPE,
                "No issue type has the name or id '" + text + "'. Use one of suggestions or call list_issue_types.",
                suggest(text, session));
    }

    private static boolean isNumber(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Up to {@value LookupLimits#MAX_SUGGESTIONS} names of issue types whose name resembles the text. */
    private List<String> suggest(String text, Session session) {
        String needle = text.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<String>();
        for (IssueType candidate : session.catalog()) {
            String name = candidate.getName() == null ? "" : candidate.getName().toLowerCase(Locale.ROOT);
            if (name.contains(needle) || (!name.isEmpty() && needle.contains(name))) {
                names.add(candidate.getName());
            }
        }
        Collections.sort(names);
        return names.size() > LookupLimits.MAX_SUGGESTIONS
                ? new ArrayList<String>(names.subList(0, LookupLimits.MAX_SUGGESTIONS)) : names;
    }
}
