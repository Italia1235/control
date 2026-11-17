package ru.rasulov.workflow.impl;

import com.fasterxml.jackson.databind.JsonNode;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.tools.ToolArguments;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.common.impl.tools.ToolProvider;
import ru.rasulov.workflow.api.*;
import ru.rasulov.workflow.api.dto.CopyWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowRequest;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsRequest;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftRequest;
import ru.rasulov.workflow.api.dto.edit.RenameWorkflowRequest;
import ru.rasulov.workflow.api.dto.edit.UpdateStatusRequest;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transport registration; domain writes are owned by their one plan/execution service. */
@Named
public class WorkflowTools implements ToolProvider {
    private final WorkflowConfigurer configurer;
    private final WorkflowStatusReader statusReader;
    private final WorkflowAssigner assigner;
    private final WorkflowCloner cloner;
    private final WorkflowDraftPublisher publisher;
    private final WorkflowReader reader;
    private final WorkflowRenamer renamer;
    private final StatusUpdater statusUpdater;
    private final WorkflowCopier copier;
    private final WorkflowValidatorReader validatorReader;
    private final WorkflowConditionReader conditionReader;
    @Inject public WorkflowTools(WorkflowConfigurer configurer, WorkflowStatusReader statusReader,
            WorkflowAssigner assigner, WorkflowCloner cloner, WorkflowDraftPublisher publisher,
            WorkflowReader reader, WorkflowRenamer renamer, StatusUpdater statusUpdater,
            WorkflowCopier copier, WorkflowValidatorReader validatorReader, WorkflowConditionReader conditionReader) {
        this.configurer=configurer;this.statusReader=statusReader;this.assigner=assigner;this.cloner=cloner;this.publisher=publisher;
        this.reader=reader;this.renamer=renamer;this.statusUpdater=statusUpdater;this.copier=copier;
        this.validatorReader=validatorReader;this.conditionReader=conditionReader;
    }
    @Override public Map<String,ToolCall> tools() {
        Map<String,ToolCall> tools=new LinkedHashMap<>();
        tools.put("list_statuses",this::listStatuses);
        tools.put("configure_workflow",args->configurer.configure(ToolArguments.parse(args,ConfigureWorkflowRequest.class)));
        tools.put("copy_workflow",args->copier.copy(ToolArguments.parse(args,CopyWorkflowRequest.class)));
        tools.put("get_project_workflows",args->{ToolArguments.allowOnly(args,"project","includeIssueCounts");return assigner.projectWorkflows(ToolArguments.text(args,"project"),ToolArguments.optionalBoolean(args,"includeIssueCounts"));});
        tools.put("assign_workflow",args->assigner.assign(ToolArguments.parse(args,AssignWorkflowRequest.class)));
        tools.put("clone_project_workflows",args->cloner.clone(ToolArguments.parse(args,CloneWorkflowsRequest.class)));
        tools.put("publish_workflow_draft",args->publisher.publish(ToolArguments.parse(args,PublishWorkflowDraftRequest.class)));
        tools.put("get_workflow",args->{ToolArguments.allowOnly(args,"workflow","version");return reader.read(ToolArguments.text(args,"workflow"),ToolArguments.text(args,"version"));});
        tools.put("rename_workflow",args->renamer.rename(ToolArguments.parse(args,RenameWorkflowRequest.class)));
        tools.put("update_status",args->statusUpdater.update(ToolArguments.parse(args,UpdateStatusRequest.class)));
        tools.put("get_workflow_validators",args->{ToolArguments.allowOnly(args,"workflow","version");return validatorReader.read(ToolArguments.text(args,"workflow"),ToolArguments.text(args,"version"));});
        tools.put("get_workflow_conditions",args->{ToolArguments.allowOnly(args,"workflow","version");return conditionReader.read(ToolArguments.text(args,"workflow"),ToolArguments.text(args,"version"));});
        return tools;
    }
    private Object listStatuses(JsonNode args) {
        ToolArguments.allowOnly(args,"query","limit","names");
        if (!args.has("names")) {
            return statusReader.listStatuses(ToolArguments.optionalText(args,"query"),ToolArguments.optionalInt(args,"limit"));
        }
        for (String other : new String[]{"query","limit"}) {
            JsonNode value = args.get(other);
            if (value != null && !value.isNull()) {
                throw new InvalidRequestException("The argument 'names' cannot be combined with '"+other+"': use names alone, or query with limit.");
            }
        }
        return statusReader.lookupStatuses(names(args.get("names")));
    }
    private static List<String> names(JsonNode value) {
        if (value.isNull()) throw new InvalidRequestException("The argument 'names' is required: an array of 1 to 30 status names.");
        if (!value.isArray()) throw new InvalidRequestException("The argument 'names' must be an array of text.");
        List<String> names = new ArrayList<>(value.size());
        for (int i = 0; i < value.size(); i++) {
            if (!value.get(i).isTextual()) throw new InvalidRequestException("The argument 'names[" + i + "]' must be text.");
            names.add(value.get(i).textValue());
        }
        return names;
    }
}
