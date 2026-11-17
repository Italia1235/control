package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.workflow.api.WorkflowConfigurer;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.function.Supplier;

/** One confirmed plan, one descriptor, one durable operation. Never calls another mutation service. */
@ExportAsService(WorkflowConfigurer.class)
@Named
public final class WorkflowConfigurerImpl implements WorkflowConfigurer {
    public static final String TOOL="configure_workflow";
    public static final String CREATE_LOCK="ru.rasulov.control.create-workflow";
    private static final String[] LOCKS={"ru.rasulov.control.assign-workflow","ru.rasulov.control.create-custom-fields",
            "ru.rasulov.control.create-standalone-screen",CREATE_LOCK};
    private final AdministratorGuard guard;
    private final NamedLock locks;
    private final WorkflowConfigurationPlanner planner;
    private final WorkflowConfigurationWriter writer;
    private final PreviewPlanExecutor executor;
    private final ChangeJournal journal;
    @Inject
    public WorkflowConfigurerImpl(AdministratorGuard guard,NamedLock locks,WorkflowConfigurationPlanner planner,
            WorkflowConfigurationWriter writer,PreviewPlanExecutor executor,ChangeJournal journal) {
        this.guard=guard;this.locks=locks;this.planner=planner;this.writer=writer;this.executor=executor;this.journal=journal;
    }
    @Override public ConfigureWorkflowResponse configure(ConfigureWorkflowRequest request) {
        ApplicationUser user=guard.requireAdministrator();requireMode(request);
        if(request.dryRun()) {
            WorkflowConfigurationPlan plan=planner.plan(request,user);
            return plan.applicable()?executor.issue(TOOL,user.getKey(),request,plan.fingerprint,plan.response::withPlan):plan.response;
        }
        return journal.execute(TOOL,user.getKey(),()->locked(0,()->executor.apply(TOOL,user.getKey(),request.planId(),
                ConfigureWorkflowRequest.class,ConfigureWorkflowResponse::stale,r->planner.plan(r,user),
                WorkflowConfigurationPlan::applicable,p->p.fingerprint,p->writer.write(p,user))));
    }
    private <T> T locked(int at,Supplier<T> action) {
        return at==LOCKS.length?action.get():locks.run(LOCKS[at],"workflow and screen configuration",()->locked(at+1,action));
    }
    private static void requireMode(ConfigureWorkflowRequest request) {
        if(request==null || request.dryRun()==null)throw new InvalidRequestException("dryRun must be explicitly true or false.");
        if(request.dryRun()) {
            if(request.planId()!=null)throw new InvalidRequestException("Preview takes complete workflow details, not planId.");
        } else if(request.planId()==null || request.planId().isBlank() || request.create()!=null || request.existing()!=null
                || request.newScreens()!=null || request.transitionRules()!=null)
            throw new InvalidRequestException("Apply takes dryRun=false and planId only, through start_operation.");
    }
}
