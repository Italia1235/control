package ru.rasulov.common.impl.issuetype;

import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView;
import ru.rasulov.common.impl.ChangeLinks;

import java.util.List;
import java.util.function.Supplier;

/**
 * The complete issue-type set of a project that another feature creates: resolve it before any plan is issued, then
 * apply it to the new project inside the caller's journal operation. Implemented by the issue-type feature, so a
 * feature that needs a set depends on this contract only. Error codes are the names of the
 * {@code set_project_issue_types} error codes.
 */
public interface IssueTypeSets {

    /**
     * Resolves existing references, new definitions and the default against Jira in one pass. Writes nothing;
     * validation problems are returned in the result, not thrown.
     */
    Resolved resolve(List<String> references, List<IssueTypeDefinition> definitions, String requestedDefault);

    /**
     * Runs {@code work} holding the issue-type locks in the fixed order every caller and undo handler uses: global
     * issue types, then project issue types. Locks of the caller's own area are taken inside {@code work}.
     */
    <T> T underLocks(Supplier<T> work);

    /**
     * Creates the missing types and assigns the whole set to a project that has just been created. Takes no lock and
     * issues no plan: the caller holds {@link #underLocks} and has compared the fingerprint. Jira and domain failures
     * are a result, never an exception; only journal cancellation and capacity errors propagate.
     *
     * @param set a valid result of {@link #resolve} of this implementation
     */
    Applied applyToNewProject(Project project, Resolved set, ApplicationUser user, ChangeLinks.Report report);

    /** A resolved set; opaque to the caller beyond these accessors. */
    interface Resolved {
        boolean valid();
        /** Name of the error code; null exactly when valid. */
        String errorCode();
        String message();
        List<String> suggestions();
        /** Everything that makes two previews of this set equal: decisions, the catalog snapshot and the default. */
        List<Object> fingerprintSource();
        /** The plan for a project that does not exist yet. */
        IssueTypeSetView preview();
    }

    /** What applying the set really did. */
    interface Applied {
        /** True only when the final set is assigned and every type of it is confirmed. */
        boolean complete();
        /** Name of the error code; null when complete. */
        String errorCode();
        String message();
        IssueTypeSetView view();
    }
}
