package ru.rasulov.projects.impl;

import com.fasterxml.jackson.databind.JsonNode;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.tools.ToolArguments;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.common.impl.tools.ToolProvider;
import ru.rasulov.projects.api.ProjectCreator;
import ru.rasulov.projects.api.dto.CreateProjectRequest;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.LinkedHashMap;
import java.util.Map;

/** MCP tools backed by the same project service as the REST endpoints. */
@Named
public class ProjectTools implements ToolProvider {

    private final ProjectCreator creator;

    @Inject
    public ProjectTools(ProjectCreator creator) {
        this.creator = creator;
    }

    @Override
    public Map<String, ToolCall> tools() {
        Map<String, ToolCall> tools = new LinkedHashMap<String, ToolCall>();
        tools.put("list_project_leads", arguments -> {
            ToolArguments.allowOnly(arguments, "query");
            return creator.listLeads(ToolArguments.text(arguments, "query"));
        });
        tools.put("create_project", this::createProject);
        return tools;
    }

    /**
     * One tool, three mutually exclusive shapes: {@code {action:"list_templates"}} alone (read-only catalog),
     * {@code {action:"create",templateKey,dryRun:true,...}} (preview) and the plan-only apply
     * {@code {dryRun:false,planId}} that start_operation sends. The form without an action is not accepted.
     */
    private Object createProject(JsonNode arguments) {
        JsonNode action = arguments.get("action");
        if (action == null) {
            JsonNode dryRun = arguments.get("dryRun");
            boolean planApply = arguments.size() == 2 && dryRun != null && dryRun.isBoolean()
                    && !dryRun.booleanValue() && arguments.has("planId");
            if (!planApply) {
                throw new InvalidRequestException("create_project takes action=\"list_templates\" alone, or "
                        + "action=\"create\" with a templateKey from list_templates; the form without an action is "
                        + "not accepted. Applying a confirmed plan is done only through start_operation.");
            }
            return creator.create(ToolArguments.parse(arguments, CreateProjectRequest.class));
        }
        if (!action.isTextual()) {
            throw new InvalidRequestException("The argument 'action' must be \"list_templates\" or \"create\".");
        }
        switch (action.textValue()) {
            case CreateProjectRequest.ACTION_LIST_TEMPLATES:
                ToolArguments.allowOnly(arguments, "action");
                return creator.listTemplates();
            case CreateProjectRequest.ACTION_CREATE:
                return creator.create(ToolArguments.parse(arguments, CreateProjectRequest.class));
            default:
                throw new InvalidRequestException("The argument 'action' must be \"list_templates\" or \"create\", not '"
                        + action.textValue() + "'.");
        }
    }
}
