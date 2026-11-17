package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.bc.ServiceOutcome;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.jira.bc.workflow.WorkflowSchemeService;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.scheme.Scheme;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.impl.undo.ChangeJournal;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Creates, changes and assigns workflow schemes. Creation goes through {@code WorkflowSchemeService},
 * so Jira's own checks (name, workflows exist) apply. Changing does not: the service
 * refuses to change the mappings of an active scheme ("The mappings of an active workflow scheme cannot be changed",
 * Jira wants a draft and a migration), and the project's own scheme is always active. Here the issues have been
 * moved by this plugin before the change, so it goes through {@code WorkflowSchemeManager}. Giving a scheme to a
 * project is what the manager offers too ({@code removeSchemesFromProject} + {@code addSchemeToProject}: a project has
 * one scheme, and adding does not replace). Each method throws {@link IllegalStateException} or a Jira runtime
 * exception when Jira refuses; the Jira messages go to the log, not to the caller.
 */
@Named
public class SchemeWriter {

    private final WorkflowSchemeManager schemeManager;
    private final WorkflowSchemeService schemeService;
    private final ChangeJournal journal;

    @Inject
    public SchemeWriter(@ComponentImport WorkflowSchemeManager schemeManager,
                        @ComponentImport WorkflowSchemeService schemeService, ChangeJournal journal) {
        this.schemeManager = schemeManager;
        this.schemeService = schemeService;
        this.journal = journal;
    }

    /**
     * Creates a scheme that maps every issue type as {@code basis} does, except for what {@code edit} changes. The
     * scheme belongs to no project yet.
     */
    AssignableWorkflowScheme create(ApplicationUser user, String name, AssignableWorkflowScheme basis, SchemeEdit edit) {
        AssignableWorkflowScheme.Builder builder = schemeService.assignableBuilder().setName(name);
        if (basis.getDescription() != null) {
            builder.setDescription(basis.getDescription());
        }
        if (basis.getConfiguredDefaultWorkflow() != null) {
            builder.setDefaultWorkflow(basis.getConfiguredDefaultWorkflow());
        }
        builder.setMappings(basis.getMappings());
        edit.apply(builder);
        AssignableWorkflowScheme created = valueOf(schemeService.createScheme(user, builder.build()), "create scheme '" + name + "'");
        created = schemeManager.getWorkflowSchemeObj(created.getId());
        journal.checkpoint();
        return created;
    }

    /**
     * Changes the scheme in place: {@code basis} with {@code edit} applied. After {@code updateWorkflowScheme} Jira's
     * cache of active workflows stays stale (observed on 9.12: the workflow just assigned is reported inactive until
     * some other scheme change resets the cache). Then "Edit" opens the workflow live instead of making a draft, and a
     * draft made later cannot be published ("The parent workflow of draft ... is no longer active"). So the cache is
     * reset here.
     */
    void update(AssignableWorkflowScheme basis, SchemeEdit edit) {
        AssignableWorkflowScheme.Builder builder = basis.builder();
        edit.apply(builder);
        AssignableWorkflowScheme expected = builder.build();
        schemeManager.updateWorkflowScheme(expected);
        schemeManager.clearWorkflowCache();
        journal.checkpoint();
    }


    /**
     * No cooperative cancellation inside the binding replacement; native failures are not rolled back. Like
     * {@link #update}, it resets Jira's cache of active workflows: {@code removeSchemesFromProject} and
     * {@code addSchemeToProject} flush only the project-scheme caches, so without the reset the assigned workflows stay
     * "inactive" while the project already uses them. The reset also runs when remove/add fails half-way (the binding
     * may have changed); a failure of the reset itself never hides the original exception.
     */
    void assign(Project project, AssignableWorkflowScheme scheme) {
        Scheme target = schemeManager.getSchemeObject(scheme.getId());
        journal.checkpoint();
        try {
            schemeManager.removeSchemesFromProject(project);
            schemeManager.addSchemeToProject(project, target);
        } catch (RuntimeException | Error e) {
            try {
                schemeManager.clearWorkflowCache();
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        schemeManager.clearWorkflowCache();
        journal.checkpoint();
    }


    private static IllegalStateException refused(String what, ServiceOutcome<?> outcome) {
        ErrorCollection errors = outcome.getErrorCollection();
        return new IllegalStateException("Jira refused to " + what + ": " + errors.getErrorMessages() + " "
                + errors.getErrors() + " " + errors.getReasons());
    }

    private static <T> T valueOf(ServiceOutcome<T> outcome, String what) {
        if (!outcome.isValid()) {
            throw refused(what, outcome);
        }
        return outcome.getReturnedValue();
    }
}
