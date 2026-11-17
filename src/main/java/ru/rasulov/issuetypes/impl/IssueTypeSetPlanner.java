package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.issue.issuetype.IssueType;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView.Origin;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.ErrorCode;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a whole wanted project issue-type set (existing references plus new definitions plus the default) in one
 * pass, before any plan is issued or anything is written. The same domain step serves {@code create_project} and
 * {@code set_project_issue_types}; it calls no tool and issues no plan. Validation problems are returned in the
 * {@link ResolvedTypeSet}, not thrown.
 */
@Named
public class IssueTypeSetPlanner {
    static final int MAX_SET = IssueTypeChangePlanner.MAX_ISSUE_TYPES;

    private final IssueTypeResolver resolver;
    private final IssueTypeBatchPlanner batchPlanner;

    @Inject
    public IssueTypeSetPlanner(IssueTypeResolver resolver, IssueTypeBatchPlanner batchPlanner) {
        this.resolver = resolver;
        this.batchPlanner = batchPlanner;
    }

    public ResolvedTypeSet resolve(List<String> references, List<IssueTypeDefinition> definitions, String requestedDefault) {
        String wantedDefault = requestedDefault == null || requestedDefault.trim().isEmpty() ? null : requestedDefault.trim();
        List<ResolvedTypeSet.Member> members = new ArrayList<ResolvedTypeSet.Member>();
        Set<String> ids = new HashSet<String>();
        IssueTypeResolver.Session session = resolver.session();
        for (String reference : references) {
            IssueType type;
            try {
                type = session.resolve(reference);
            } catch (LookupFailure e) {
                return failed(members, null, wantedDefault, e.getKind() == LookupFailure.Kind.UNKNOWN_ISSUE_TYPE
                        ? ErrorCode.UNKNOWN_ISSUE_TYPE : ErrorCode.VALIDATION_ERROR, "issueTypes: " + e.getMessage(),
                        e.getSuggestions());
            }
            if (!ids.add(type.getId())) {
                return failed(members, null, wantedDefault, ErrorCode.VALIDATION_ERROR,
                        "Issue type '" + type.getName() + "' appears more than once in the set.", null);
            }
            members.add(new ResolvedTypeSet.Member(null, type.getName(), type.isSubTask(), Origin.REFERENCE, type, null));
        }
        IssueTypeBatchPlanner.Plan batch = null;
        if (!definitions.isEmpty()) {
            batch = batchPlanner.resolve(definitions);
            if (!batch.valid()) {
                return failed(members, batch, wantedDefault, ErrorCode.VALIDATION_ERROR, "newIssueTypes: " + batch.message
                        + " Fix the items and preview again.", null);
            }
            for (IssueTypeBatchPlanner.Item item : batch.items) {
                if (item.action == IssueTypeBatchPlanner.Action.REUSE && !ids.add(item.existing.getId())) {
                    return failed(members, batch, wantedDefault, ErrorCode.VALIDATION_ERROR, "Issue type '"
                            + item.existing.getName() + "' appears more than once in the set (once as a reference, "
                            + "once as a definition).", null);
                }
                members.add(new ResolvedTypeSet.Member(item.key, item.name, item.subtask, Origin.DEFINITION,
                        item.action == IssueTypeBatchPlanner.Action.REUSE ? item.existing : null, item));
            }
        }
        if (members.isEmpty()) {
            return failed(members, batch, wantedDefault, ErrorCode.VALIDATION_ERROR,
                    "The set must contain at least one issue type: list existing types in 'issueTypes' and/or "
                            + "definitions in 'newIssueTypes'.", null);
        }
        if (members.size() > MAX_SET) {
            return failed(members, batch, wantedDefault, ErrorCode.VALIDATION_ERROR,
                    "One call accepts at most " + MAX_SET + " issue types.", null);
        }
        boolean anyStandard = false;
        for (ResolvedTypeSet.Member member : members) if (!member.subtask) anyStandard = true;
        if (!anyStandard) {
            return failed(members, batch, wantedDefault, ErrorCode.VALIDATION_ERROR,
                    "The set has only sub-task types. A project needs at least one standard issue type, which also "
                            + "becomes its default.", null);
        }
        ResolvedTypeSet draft = new ResolvedTypeSet(members, batch, wantedDefault, null, null, null);
        if (wantedDefault != null) {
            ResolvedTypeSet.Member chosen = draft.requestedDefaultMember();
            if (chosen == null) {
                return failed(members, batch, wantedDefault, ErrorCode.VALIDATION_ERROR, "defaultIssueType '"
                        + wantedDefault + "' is not in the set. Give the local key of a new definition or the name or id "
                        + "of a type of the set.", names(members));
            }
            if (chosen.subtask) {
                return failed(members, batch, wantedDefault, ErrorCode.VALIDATION_ERROR, "defaultIssueType '"
                        + wantedDefault + "' is a sub-task type. The default must be a standard issue type.", null);
            }
        }
        return draft;
    }

    private static ResolvedTypeSet failed(List<ResolvedTypeSet.Member> members, IssueTypeBatchPlanner.Plan batch,
                                          String requestedDefault, ErrorCode code, String message, List<String> suggestions) {
        return new ResolvedTypeSet(members, batch, requestedDefault, code, message,
                suggestions == null ? Collections.<String>emptyList() : suggestions);
    }

    private static List<String> names(List<ResolvedTypeSet.Member> members) {
        List<String> names = new ArrayList<String>();
        for (ResolvedTypeSet.Member member : members) names.add(member.key == null ? member.name : member.key);
        return names;
    }
}
