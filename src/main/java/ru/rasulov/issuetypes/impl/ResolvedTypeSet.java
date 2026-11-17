package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.issue.issuetype.IssueType;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.ItemResult;
import ru.rasulov.common.api.dto.issuetype.ItemStatus;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView.Entry;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView.Origin;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.ErrorCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A complete wanted issue-type set resolved against Jira before anything is written: existing references, definitions
 * that will be reused or created, the default type, and the single catalog snapshot the decision was made on.
 * Local keys stand in for Jira ids that do not exist yet. Immutable; produced by {@link IssueTypeSetPlanner}.
 */
public final class ResolvedTypeSet {
    /** One member of the set, in request order. {@code type} is null exactly while the type must still be created. */
    static final class Member {
        final String key;
        final String name;
        final boolean subtask;
        final Origin origin;
        final IssueType type;
        final IssueTypeBatchPlanner.Item item;

        Member(String key, String name, boolean subtask, Origin origin, IssueType type, IssueTypeBatchPlanner.Item item) {
            this.key = key; this.name = name; this.subtask = subtask; this.origin = origin; this.type = type; this.item = item;
        }

        boolean toCreate() { return type == null; }
    }

    final List<Member> members;
    final IssueTypeBatchPlanner.Plan batch;
    /** The requested default as written by the caller (local key, name or id), or null. */
    final String requestedDefault;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;

    ResolvedTypeSet(List<Member> members, IssueTypeBatchPlanner.Plan batch, String requestedDefault, ErrorCode errorCode,
                    String message, List<String> suggestions) {
        this.members = members;
        this.batch = batch;
        this.requestedDefault = requestedDefault;
        this.errorCode = errorCode;
        this.message = message;
        this.suggestions = suggestions == null ? Collections.<String>emptyList() : suggestions;
    }

    public boolean valid() { return errorCode == null; }
    public ErrorCode errorCode() { return errorCode; }
    public String message() { return message; }
    public List<String> suggestions() { return suggestions; }

    /** Types that do not exist yet and will be created by the apply. */
    public int creates() {
        int count = 0;
        for (Member member : members) if (member.toCreate()) count++;
        return count;
    }

    /** True when the caller listed new-type definitions (even if they all resolved to existing types). */
    public boolean hasDefinitions() { return batch != null; }

    /** Per-item results of a rejected batch, so the offending definitions are marked; empty otherwise. */
    public List<ItemResult> itemResults() {
        return batch == null ? Collections.<ItemResult>emptyList() : batch.preview();
    }

    /** Everything that makes two previews of this set equal: decisions, the whole catalog snapshot and the default. */
    public List<Object> fingerprintSource() {
        List<Object> source = new ArrayList<Object>();
        source.add(batch == null ? null : batch.fingerprintSource());
        List<Object> existing = new ArrayList<Object>();
        for (Member member : members) {
            existing.add(member.key + "|" + member.name + "|" + member.subtask + "|" + member.origin + "|"
                    + (member.type == null ? "" : member.type.getId()));
        }
        source.add(existing);
        source.add(requestedDefault);
        return source;
    }

    /** Ids of members that already exist, in set order. */
    List<String> existingIds() {
        List<String> ids = new ArrayList<String>();
        for (Member member : members) if (member.type != null) ids.add(member.type.getId());
        return ids;
    }

    /** Members that do not exist yet, as id-less references (for plans made before they are created). */
    List<IssueTypeRef> pending() {
        List<IssueTypeRef> pending = new ArrayList<IssueTypeRef>();
        for (Member member : members) if (member.toCreate()) pending.add(new IssueTypeRef(null, member.name));
        return pending;
    }

    /** The member the caller asked to be the default, or null. Validated at resolution time. */
    Member requestedDefaultMember() {
        if (requestedDefault == null) return null;
        for (Member member : members) if (requestedDefault.equals(member.key)) return member;
        for (Member member : members) {
            if (member.name.equalsIgnoreCase(requestedDefault)
                    || (member.type != null && member.type.getId().equals(requestedDefault))) return member;
        }
        return null;
    }

    /** The member that will be the default, and why. */
    Member defaultMember(String previousDefaultId, String[] sourceOut) {
        Member requested = requestedDefaultMember();
        if (requested != null) {
            sourceOut[0] = "REQUESTED";
            return requested;
        }
        if (previousDefaultId != null) {
            for (Member member : members) {
                if (member.type != null && member.type.getId().equals(previousDefaultId)) {
                    sourceOut[0] = "PREVIOUS_DEFAULT";
                    return member;
                }
            }
        }
        sourceOut[0] = "FIRST_STANDARD";
        for (Member member : members) if (!member.subtask) return member;
        return members.get(0);
    }

    private String globalImpact() {
        return (creates() == 0 ? "No new global type is created: every type of the set already exists and is reused. "
                : "Each of the " + creates() + " new types is global: Jira adds it to its default issue type scheme, "
                + "so it becomes available to every project that uses that scheme. ")
                + "Other projects keep their own types: when the project's issue type scheme is Jira's default or "
                + "shared, the project gets a private scheme and the shared one is not edited. Excluding a type from "
                + "this project never deletes it.";
    }

    /**
     * The plan as shown before anything is written. {@code excluded} is null for a project that does not exist yet;
     * {@code previousDefaultId} is the current default of an existing project.
     */
    public IssueTypeSetView preview(String previousDefaultId, List<IssueTypeRef> excluded) {
        String[] source = new String[1];
        Member chosen = defaultMember(previousDefaultId, source);
        List<Entry> entries = new ArrayList<Entry>();
        for (Member member : members) {
            entries.add(new Entry(member.key, member.name, member.subtask, member.type == null ? null : member.type.getId(),
                    member.toCreate() ? ItemStatus.WOULD_CREATE : ItemStatus.EXISTS, member.origin, member == chosen,
                    member.toCreate() ? null : member.origin == Origin.DEFINITION
                            ? "Existing type; reused as it is, its name and description are not changed."
                            : null));
        }
        return new IssueTypeSetView(entries, new IssueTypeRef(chosen.type == null ? null : chosen.type.getId(), chosen.name),
                source[0], excluded, excluded == null
                ? "Which template types leave the new project is known only after it exists: every type of its issue "
                + "type scheme that is not listed here is excluded from the project and its real list is recorded in "
                + "assignment.excludedTypes. Excluding a type from a project never deletes it globally."
                : "Types that leave the project are only excluded from it; they are never deleted globally.",
                globalImpact(), residualRisks(excluded == null), null);
    }

    static List<String> residualRisks(boolean newProject) {
        List<String> risks = new ArrayList<String>();
        if (newProject) {
            risks.add("The project is created first. If a later step fails the project stays (with the template's "
                    + "types until the final set is assigned) and nothing is rolled back.");
        }
        risks.add("Global types created before a later step fails stay in Jira and in its default issue type scheme; "
                + "they are listed with their ids and are reused by a new preview of the same request.");
        risks.add("If the outcome of a write is UNKNOWN, the final set is not assigned and the dependent step is "
                + "not attempted; a new plan is required.");
        risks.add("Assigning the set is not atomic: an interrupted assignment is reported as PARTIALLY_CHANGED or "
                + "UNKNOWN with its execution receipt, and is not retried automatically.");
        return risks;
    }
}
