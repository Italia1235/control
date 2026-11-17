package ru.rasulov.screens.impl;

import com.fasterxml.jackson.databind.JsonNode;
import ru.rasulov.common.impl.tools.ToolArguments;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.common.impl.tools.ToolProvider;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.screens.api.ScreenContentsReader;
import ru.rasulov.screens.api.StandaloneScreenCreator;
import ru.rasulov.screens.api.ProjectOperationScreenAssigner;
import ru.rasulov.screens.api.IssueTypeScreenSchemeMappingEditor;
import ru.rasulov.screens.api.IssueTypeScreenSchemeAssigner;
import ru.rasulov.screens.api.ScreenSchemeOperationEditor;
import ru.rasulov.screens.api.ScreenSchemeService;
import ru.rasulov.screens.api.dto.CloneProjectScreensRequest;
import ru.rasulov.screens.api.dto.CreateScreenRequest;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeRequest;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensRequest;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsRequest;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsRequest;
import ru.rasulov.screens.api.ScreenFieldRemover;
import ru.rasulov.screens.api.ScreenLayoutEditor;
import ru.rasulov.screens.api.dto.layout.EditScreenLayoutRequest;
import ru.rasulov.screens.api.dto.layout.RemoveFieldFromScreensRequest;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.LinkedHashMap;
import java.util.Map;

/** MCP tools for project screen configuration, contents and field layout changes. */
@Named
public class ScreenTools implements ToolProvider {
    private final ScreenSchemeService service;
    private final StandaloneScreenCreator creator;
    private final ProjectOperationScreenAssigner assigner;
    private final ScreenContentsReader contentsReader;
    private final ScreenFieldRemover remover;
    private final ScreenLayoutEditor editor;
    private final IssueTypeScreenSchemeMappingEditor mappingEditor;
    private final ScreenSchemeOperationEditor operationEditor;
    private final IssueTypeScreenSchemeAssigner schemeAssigner;

    @Inject
    public ScreenTools(ScreenSchemeService service, StandaloneScreenCreator creator, ProjectOperationScreenAssigner assigner,
                       ScreenContentsReader contentsReader, ScreenFieldRemover remover, ScreenLayoutEditor editor,
                       IssueTypeScreenSchemeMappingEditor mappingEditor, ScreenSchemeOperationEditor operationEditor,
                       IssueTypeScreenSchemeAssigner schemeAssigner) {
        this.service = service;
        this.creator = creator;
        this.assigner = assigner;
        this.contentsReader = contentsReader;
        this.remover = remover;
        this.editor = editor;
        this.mappingEditor = mappingEditor;
        this.operationEditor = operationEditor;
        this.schemeAssigner = schemeAssigner;
    }

    @Override
    public Map<String, ToolCall> tools() {
        Map<String, ToolCall> tools = new LinkedHashMap<String, ToolCall>();
        tools.put("get_project_screens", arguments -> {
            ToolArguments.allowOnly(arguments, "project");
            return service.projectScreens(ToolArguments.text(arguments, "project"));
        });
        tools.put("get_screen_contents", arguments -> {
            ToolArguments.allowOnly(arguments, "screenId", "cursor");
            JsonNode id = arguments.get("screenId");
            if (id == null || !(id.isInt() || id.isLong()) || id.longValue() <= 0)
                throw new InvalidRequestException("screenId must be a positive integer from get_project_screens.");
            String cursor = ToolArguments.optionalText(arguments, "cursor");
            if (arguments.has("cursor") && (cursor == null || cursor.isEmpty() || cursor.length() > 512))
                throw new InvalidRequestException("Invalid screen cursor. Please restart without cursor.");
            return contentsReader.read(id.longValue(), cursor);
        });
        tools.put("clone_project_screens", arguments -> service.cloneProjectScreens(
                ToolArguments.parse(arguments, CloneProjectScreensRequest.class)));
        tools.put("create_screen", arguments -> creator.create(
                ToolArguments.parse(arguments, CreateScreenRequest.class)));
        tools.put("set_project_operation_screens", arguments -> assigner.assign(
                ToolArguments.parse(arguments, SetProjectOperationScreensRequest.class)));
        tools.put("set_issue_type_screen_scheme_mappings", arguments -> mappingEditor.edit(
                ToolArguments.parse(arguments, SetIssueTypeScreenSchemeMappingsRequest.class)));
        tools.put("set_screen_scheme_operations", arguments -> operationEditor.edit(
                ToolArguments.parse(arguments, SetScreenSchemeOperationsRequest.class)));
        tools.put("assign_issue_type_screen_scheme", arguments -> schemeAssigner.assign(
                ToolArguments.parse(arguments, AssignIssueTypeScreenSchemeRequest.class)));
        tools.put("remove_field_from_screens", arguments -> remover.remove(
                ToolArguments.parse(arguments, RemoveFieldFromScreensRequest.class)));
        tools.put("edit_screen_layout", arguments -> editor.edit(
                ToolArguments.parse(arguments, EditScreenLayoutRequest.class)));
        return tools;
    }
}
