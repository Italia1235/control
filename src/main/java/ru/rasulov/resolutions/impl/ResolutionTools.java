package ru.rasulov.resolutions.impl;

import ru.rasulov.common.impl.tools.ToolArguments;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.common.impl.tools.ToolProvider;
import ru.rasulov.resolutions.api.ResolutionService;
import ru.rasulov.resolutions.api.dto.CreateResolutionRequest;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.LinkedHashMap;
import java.util.Map;

@Named
public class ResolutionTools implements ToolProvider {
    private final ResolutionService service;
    @Inject public ResolutionTools(ResolutionService service) { this.service = service; }
    @Override public Map<String, ToolCall> tools() {
        Map<String, ToolCall> calls = new LinkedHashMap<String, ToolCall>();
        calls.put("get_resolutions", arguments -> {
            ToolArguments.allowOnly(arguments, "query");
            return service.get(ToolArguments.optionalText(arguments, "query"));
        });
        calls.put("create_resolution", arguments -> service.create(ToolArguments.parse(arguments, CreateResolutionRequest.class)));
        return calls;
    }
}
