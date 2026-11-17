package ru.rasulov.customfields.impl;

import com.fasterxml.jackson.databind.JsonNode;
import ru.rasulov.customfields.api.FieldContextReader;
import ru.rasulov.customfields.api.FieldContextScopeService;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsRequest;
import ru.rasulov.customfields.api.FieldContextCreator;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextRequest;
import ru.rasulov.common.impl.tools.ToolArguments;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.common.impl.tools.ToolProvider;
import ru.rasulov.customfields.api.CustomFieldCreator;
import ru.rasulov.customfields.api.FieldScreenService;
import ru.rasulov.customfields.api.FieldRequirementService;
import ru.rasulov.customfields.api.FieldConfigurationCloneService;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsRequest;
import ru.rasulov.customfields.api.dto.screen.AddToScreensRequest;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredRequest;
import ru.rasulov.customfields.api.dto.screen.Recommendation;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.common.impl.AdministratorGuard;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.LinkedHashMap;
import java.util.Map;

/** The tools of {@code customfields-tools.json}, run by the services the REST adapters use too. */
@Named
public class CustomFieldTools implements ToolProvider {

    private final CustomFieldCreator creator;
    private final FieldScreenService screens;
    private final FieldRequirementService requirements;
    private final FieldConfigurationCloneService configurationCloner;
    private final AssetsFieldConfiguration assets;
    private final AdministratorGuard guard;
    private final FieldContextReader contexts;
    private final FieldContextScopeService contextScopes;
    private final FieldContextCreator contextCreator;

    @Inject
    public CustomFieldTools(CustomFieldCreator creator, FieldScreenService screens,
                            FieldRequirementService requirements,
                            FieldConfigurationCloneService configurationCloner,
                            AssetsFieldConfiguration assets, AdministratorGuard guard, FieldContextReader contexts,
                            FieldContextScopeService contextScopes, FieldContextCreator contextCreator) {
        this.creator = creator;
        this.screens = screens;
        this.requirements = requirements;
        this.configurationCloner = configurationCloner;
        this.assets = assets;
        this.guard = guard;
        this.contexts = contexts;
        this.contextScopes = contextScopes;
        this.contextCreator = contextCreator;
    }

    @Override
    public Map<String, ToolCall> tools() {
        Map<String, ToolCall> tools = new LinkedHashMap<String, ToolCall>();
        tools.put("list_custom_field_types", arguments -> {
            ToolArguments.allowOnly(arguments);
            return creator.listAvailableTypes();
        });
        tools.put("list_assets_schemas", arguments -> {
            ToolArguments.allowOnly(arguments);
            guard.requireAdministrator();
            return assets.listSchemas();
        });
        tools.put("get_field_contexts", arguments -> {
            ToolArguments.allowOnly(arguments, "field", "project", "contextId", "includeOptions");
            String field = ToolArguments.text(arguments, "field");
            String project = arguments.has("project") ? ToolArguments.text(arguments, "project") : null;
            JsonNode id = arguments.get("contextId");
            if (id != null && (!(id.isInt() || id.isLong()) || id.longValue() <= 0))
                throw new InvalidRequestException("contextId must be a positive integer from get_field_contexts.");
            JsonNode include = arguments.get("includeOptions");
            if (include != null && !include.isBoolean())
                throw new InvalidRequestException("includeOptions must be true or false.");
            return contexts.read(field, project, id == null ? null : id.longValue(), include != null && include.booleanValue());
        });
        tools.put("update_field_contexts", arguments ->
                contextScopes.update(ToolArguments.parse(arguments, UpdateFieldContextsRequest.class)));
        tools.put("create_field_context", arguments ->
                contextCreator.create(ToolArguments.parse(arguments, CreateFieldContextRequest.class)));
        tools.put("create_custom_fields",
                arguments -> creator.createAll(ToolArguments.parse(arguments, CreateCustomFieldsRequest.class)));
        tools.put("plan_field_screens", arguments -> {
            ToolArguments.allowOnly(arguments, "fieldId", "cursor", "recommendation");
            String fieldId = ToolArguments.text(arguments, "fieldId");
            String cursor = ToolArguments.optionalText(arguments, "cursor");
            String filter = ToolArguments.optionalText(arguments, "recommendation");
            if (filter != null && !"RECOMMENDED".equals(filter)) {
                throw new InvalidRequestException("'recommendation' must be RECOMMENDED or omitted.");
            }
            return cursor == null && filter == null ? screens.planScreens(fieldId)
                    : screens.planScreens(fieldId, cursor, filter == null ? null : Recommendation.RECOMMENDED);
        });
        tools.put("add_field_to_screens",
                arguments -> screens.addToScreens(ToolArguments.parse(arguments, AddToScreensRequest.class)));
        tools.put("set_field_required",
                arguments -> requirements.setRequired(ToolArguments.parse(arguments, SetFieldRequiredRequest.class)));
        tools.put("clone_project_field_configurations", arguments ->
                configurationCloner.cloneProjectFieldConfigurations(ToolArguments.parse(
                        arguments, CloneProjectFieldConfigurationsRequest.class)));
        return tools;
    }
}
