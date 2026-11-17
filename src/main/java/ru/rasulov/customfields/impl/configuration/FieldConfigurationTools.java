package ru.rasulov.customfields.impl.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.tools.ToolArguments;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.common.impl.tools.ToolProvider;
import ru.rasulov.customfields.api.FieldConfigurationCreator;
import ru.rasulov.customfields.api.FieldConfigurationReader;
import ru.rasulov.customfields.api.FieldConfigurationSchemeAssigner;
import ru.rasulov.customfields.api.dto.configuration.AssignFieldConfigurationSchemeRequest;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationRequest;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationSchemeRequest;
import ru.rasulov.customfields.api.dto.configuration.SetFieldConfigurationMappingsRequest;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Field configuration and field configuration scheme tools of {@code customfields-tools.json}. */
@Named
public class FieldConfigurationTools implements ToolProvider {
    private final FieldConfigurationReader reader;
    private final FieldConfigurationCreator creator;
    private final FieldConfigurationSchemeAssigner assigner;

    @Inject
    public FieldConfigurationTools(FieldConfigurationReader reader, FieldConfigurationCreator creator,
                                   FieldConfigurationSchemeAssigner assigner) {
        this.reader = reader;
        this.creator = creator;
        this.assigner = assigner;
    }

    @Override
    public Map<String, ToolCall> tools() {
        Map<String, ToolCall> tools = new LinkedHashMap<String, ToolCall>();
        tools.put("get_field_configurations", this::getFieldConfigurations);
        tools.put("create_field_configuration", arguments ->
                creator.createConfiguration(ToolArguments.parse(arguments, CreateFieldConfigurationRequest.class)));
        tools.put("create_field_configuration_scheme", arguments ->
                creator.createScheme(ToolArguments.parse(arguments, CreateFieldConfigurationSchemeRequest.class)));
        tools.put("assign_field_configuration_scheme", arguments ->
                assigner.assign(ToolArguments.parse(arguments, AssignFieldConfigurationSchemeRequest.class)));
        tools.put("set_field_configuration_mappings", arguments ->
                assigner.setMappings(ToolArguments.parse(arguments, SetFieldConfigurationMappingsRequest.class)));
        return tools;
    }

    /** Two exclusive modes: {project[, fields]} reads one project; {kind, id|name[, offset, limit]} searches. */
    private Object getFieldConfigurations(JsonNode arguments) {
        ToolArguments.allowOnly(arguments, "project", "fields", "kind", "id", "name", "offset", "limit",
                "usageOffset", "usageLimit");
        boolean project = arguments.has("project") || arguments.has("fields");
        boolean search = arguments.has("kind") || arguments.has("id") || arguments.has("name")
                || arguments.has("offset") || arguments.has("limit") || arguments.has("usageOffset")
                || arguments.has("usageLimit");
        if (project == search) {
            throw new InvalidRequestException("Give either 'project' (optionally 'fields') to read one project, or "
                    + "'kind' with 'id' or 'name' to search; the two modes cannot be mixed.");
        }
        if (project) return reader.read(ToolArguments.text(arguments, "project"), texts(arguments, "fields"));

        String kindText = ToolArguments.text(arguments, "kind");
        FieldConfigurationSearchResponse.Kind kind;
        try {
            kind = FieldConfigurationSearchResponse.Kind.valueOf(kindText);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("'kind' must be CONFIGURATION or SCHEME, not '" + kindText + "'.");
        }
        String id = ToolArguments.optionalText(arguments, "id");
        String name = ToolArguments.optionalText(arguments, "name");
        if ((id == null) == (name == null)) {
            throw new InvalidRequestException("Give exactly one of 'id' and 'name' with 'kind'.");
        }
        if (id != null) {
            if (arguments.has("offset") || arguments.has("limit")) {
                throw new InvalidRequestException("'offset' and 'limit' page a search by 'name'; an 'id' finds one object.");
            }
            if (!id.matches("DEFAULT|[1-9][0-9]{0,17}")) {
                throw new InvalidRequestException("'id' must be a positive whole number or DEFAULT, not '" + id + "'.");
            }
            Integer usageOffset = ToolArguments.optionalInt(arguments, "usageOffset");
            Integer usageLimit = ToolArguments.optionalInt(arguments, "usageLimit");
            if (usageOffset != null && usageOffset < 0) throw new InvalidRequestException("'usageOffset' must be 0 or more.");
            if (usageLimit != null && (usageLimit < 1 || usageLimit > FieldConfigurationSearcher.MAX_USAGE_LIMIT)) {
                throw new InvalidRequestException("'usageLimit' must be between 1 and "
                        + FieldConfigurationSearcher.MAX_USAGE_LIMIT + ".");
            }
            return reader.search(kind, id, null, 0, 1, usageOffset == null ? 0 : usageOffset,
                    usageLimit == null ? FieldConfigurationSearcher.DEFAULT_USAGE_LIMIT : usageLimit);
        }
        if (arguments.has("usageOffset") || arguments.has("usageLimit")) {
            throw new InvalidRequestException("'usageOffset' and 'usageLimit' page the usage of one object found by 'id'.");
        }
        if (name.trim().isEmpty()) throw new InvalidRequestException("'name' must not be blank.");
        Integer offset = ToolArguments.optionalInt(arguments, "offset");
        Integer limit = ToolArguments.optionalInt(arguments, "limit");
        if (offset != null && offset < 0) throw new InvalidRequestException("'offset' must be 0 or more.");
        if (limit != null && (limit < 1 || limit > FieldConfigurationSearcher.MAX_LIMIT)) {
            throw new InvalidRequestException("'limit' must be between 1 and " + FieldConfigurationSearcher.MAX_LIMIT + ".");
        }
        return reader.search(kind, null, name, offset == null ? 0 : offset,
                limit == null ? FieldConfigurationSearcher.DEFAULT_LIMIT : limit, 0,
                FieldConfigurationSearcher.DEFAULT_USAGE_LIMIT);
    }

    private static List<String> texts(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        List<String> result = new ArrayList<String>();
        if (value == null || value.isNull()) return result;
        if (!value.isArray()) throw new InvalidRequestException("'" + name + "' must be an array of text.");
        for (JsonNode item : value) {
            if (!item.isTextual()) throw new InvalidRequestException("Every element of '" + name + "' must be text.");
            result.add(item.textValue());
        }
        return result;
    }
}
