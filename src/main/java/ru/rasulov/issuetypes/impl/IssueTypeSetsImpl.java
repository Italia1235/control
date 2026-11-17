package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView;
import ru.rasulov.common.impl.ChangeLinks;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.issuetype.IssueTypeSets;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest.TypeMapping;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.ErrorCode;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** {@link IssueTypeSets} over {@link IssueTypeSetPlanner} and {@link IssueTypeSetExecutor}. */
@Named
public class IssueTypeSetsImpl implements IssueTypeSets {
    private final IssueTypeSetPlanner planner;
    private final IssueTypeSetExecutor executor;
    private final NamedLock lock;

    @Inject
    public IssueTypeSetsImpl(IssueTypeSetPlanner planner, IssueTypeSetExecutor executor, NamedLock lock) {
        this.planner = planner;
        this.executor = executor;
        this.lock = lock;
    }

    @Override
    public Resolved resolve(List<String> references, List<IssueTypeDefinition> definitions, String requestedDefault) {
        return new ResolvedSet(planner.resolve(references, definitions, requestedDefault));
    }

    @Override
    public <T> T underLocks(Supplier<T> work) {
        return lock.run(IssueTypeCreatorImpl.LOCK_NAME, "global issue types", () ->
                lock.run(ProjectIssueTypeServiceImpl.LOCK_NAME, "project issue types", work));
    }

    @Override
    public Applied applyToNewProject(Project project, Resolved set, ApplicationUser user, ChangeLinks.Report report) {
        return new AppliedSet(executor.apply(project, ((ResolvedSet) set).set,
                Collections.<TypeMapping>emptyList(), null, user, report));
    }

    private static String name(ErrorCode code) {
        return code == null ? null : code.name();
    }

    private static final class ResolvedSet implements Resolved {
        private final ResolvedTypeSet set;

        private ResolvedSet(ResolvedTypeSet set) {
            this.set = set;
        }

        @Override public boolean valid() { return set.valid(); }
        @Override public String errorCode() { return name(set.errorCode()); }
        @Override public String message() { return set.message(); }
        @Override public List<String> suggestions() { return set.suggestions(); }
        @Override public List<Object> fingerprintSource() { return set.fingerprintSource(); }
        @Override public IssueTypeSetView preview() { return set.preview(null, null); }
    }

    private static final class AppliedSet implements Applied {
        private final IssueTypeSetExecutor.Applied applied;

        private AppliedSet(IssueTypeSetExecutor.Applied applied) {
            this.applied = applied;
        }

        @Override public boolean complete() { return applied.complete(); }
        @Override public String errorCode() { return name(applied.errorCode()); }
        @Override public String message() { return applied.message(); }
        @Override public IssueTypeSetView view() { return applied.view(); }
    }
}
