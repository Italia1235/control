package ru.rasulov.issuetypes.impl;

import ru.rasulov.common.impl.tools.ToolArguments;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.common.impl.tools.ToolProvider;
import ru.rasulov.issuetypes.api.IssueTypeCreator;
import ru.rasulov.issuetypes.api.ProjectIssueTypeService;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesRequest;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.LinkedHashMap;
import java.util.Map;

/** MCP provider for global issue type creation and project issue-type changes. */
@Named
public class IssueTypeTools implements ToolProvider {
    private final ProjectIssueTypeService service;
    private final IssueTypeCreator creator;

    @Inject
    public IssueTypeTools(ProjectIssueTypeService service, IssueTypeCreator creator) {
        this.service = service;
        this.creator = creator;
    }

    @Override
    public Map<String, ToolCall> tools() {
        Map<String, ToolCall> tools = new LinkedHashMap<String, ToolCall>();
        tools.put("set_project_issue_types",
                arguments -> service.set(ToolArguments.parse(arguments, SetProjectIssueTypesRequest.class)));
        tools.put("create_issue_types",
                arguments -> creator.create(ToolArguments.parse(arguments, CreateIssueTypesRequest.class)));
        return tools;
    }
}
