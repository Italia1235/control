package ru.rasulov.common.impl;

import ru.rasulov.customfields.api.CustomFieldCreator;
import ru.rasulov.customfields.api.FieldScreenService;
import ru.rasulov.customfields.api.dto.CustomFieldTypeResponse;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.customfields.api.dto.screen.AddToScreensRequest;
import ru.rasulov.customfields.api.dto.screen.AddToScreensResponse;
import ru.rasulov.customfields.api.dto.screen.ScreenPlan;
import ru.rasulov.workflow.api.WorkflowAssigner;
import ru.rasulov.workflow.api.WorkflowCloner;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowRequest;
import ru.rasulov.workflow.api.dto.assign.ProjectWorkflowsResponse;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowResponse;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsRequest;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsResponse;

import java.util.List;
import java.util.function.Function;

/** Keeps pre-plan behavior tests focused on their domain by performing the required preview/apply handshake. */
public final class LegacyWriteCalls {
    private LegacyWriteCalls() {}

    public static CustomFieldCreator fields(CustomFieldCreator delegate) {
        return new CustomFieldCreator() {
            @Override
            public CreateCustomFieldsResponse createAll(CreateCustomFieldsRequest request) {
                return fields(request, delegate::createAll);
            }

            @Override
            public List<CustomFieldTypeResponse> listAvailableTypes() {
                return delegate.listAvailableTypes();
            }
        };
    }


    public static FieldScreenService screens(FieldScreenService delegate) {
        return new FieldScreenService() {
            @Override
            public ScreenPlan planScreens(String fieldId) {
                return delegate.planScreens(fieldId);
            }

            @Override
            public ScreenPlan planScreens(String fieldId, String cursor,
                    ru.rasulov.customfields.api.dto.screen.Recommendation recommendation) {
                return delegate.planScreens(fieldId, cursor, recommendation);
            }

            @Override
            public AddToScreensResponse addToScreens(AddToScreensRequest request) {
                return screens(request, delegate::addToScreens);
            }
        };
    }

    public static WorkflowAssigner assignment(WorkflowAssigner delegate) {
        return new WorkflowAssigner() {
            @Override
            public ProjectWorkflowsResponse projectWorkflows(String project, boolean includeIssueCounts) {
                return delegate.projectWorkflows(project, includeIssueCounts);
            }

            @Override
            public AssignWorkflowResponse assign(AssignWorkflowRequest request) {
                return assignment(request, delegate::assign);
            }
        };
    }

    public static WorkflowCloner clone(WorkflowCloner delegate) {
        return request -> clone(request, delegate::clone);
    }

    public static CreateCustomFieldsResponse fields(CreateCustomFieldsRequest r,
            Function<CreateCustomFieldsRequest, CreateCustomFieldsResponse> call) {
        if (r == null || !Boolean.FALSE.equals(r.isDryRun()) || r.getPlanId() != null) return call.apply(r);
        CreateCustomFieldsResponse p = call.apply(new CreateCustomFieldsRequest(true, r.getFields()));
        return p.getPlanId() == null ? p : call.apply(new CreateCustomFieldsRequest(false, null, p.getPlanId()));
    }

    public static AddToScreensResponse screens(AddToScreensRequest r,
            Function<AddToScreensRequest, AddToScreensResponse> call) {
        if (r == null || !Boolean.FALSE.equals(r.isDryRun()) || r.getPlanId() != null) return call.apply(r);
        AddToScreensResponse p = call.apply(new AddToScreensRequest(r.getFieldIds(), true, r.getScreens()));
        return p.getPlanId() == null ? p : call.apply(new AddToScreensRequest(null, false, null, p.getPlanId()));
    }


    public static AssignWorkflowResponse assignment(AssignWorkflowRequest r,
            Function<AssignWorkflowRequest, AssignWorkflowResponse> call) {
        if (r == null || !Boolean.FALSE.equals(r.isDryRun()) || r.getPlanId() != null) return call.apply(r);
        AssignWorkflowResponse p = call.apply(new AssignWorkflowRequest(true, r.getProject(), r.getWorkflow(),
                r.isAllIssueTypes(), r.getIssueTypes(), r.isNewScheme(), r.getStatusMappings(), null));
        return p.getPlanId() == null ? p : call.apply(new AssignWorkflowRequest(false, null, null, false, null, false,
                p.getPlanId()));
    }

    public static CloneWorkflowsResponse clone(CloneWorkflowsRequest r,
            Function<CloneWorkflowsRequest, CloneWorkflowsResponse> call) {
        if (r == null || !Boolean.FALSE.equals(r.isDryRun()) || r.getPlanId() != null) return call.apply(r);
        CloneWorkflowsResponse p = call.apply(new CloneWorkflowsRequest(true, r.getProject(), r.getScope(),
                r.getWorkflows(), r.getSchemeName(), r.getWorkflowNames()));
        return p.getPlanId() == null ? p : call.apply(new CloneWorkflowsRequest(false, null, null, null, null, null,
                p.getPlanId()));
    }
}
