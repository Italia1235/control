package ru.rasulov.common.impl.lookup;

import ru.rasulov.common.api.LookupService;
import ru.rasulov.common.impl.tools.ToolArguments;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.common.impl.tools.ToolProvider;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.LinkedHashMap;
import java.util.Map;

/** The lookup tools of {@code common-tools.json}: {@code list_projects}, {@code list_issue_types}. */
@Named
public class LookupTools implements ToolProvider {

    private final LookupService lookupService;

    @Inject
    public LookupTools(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    @Override
    public Map<String, ToolCall> tools() {
        Map<String, ToolCall> tools = new LinkedHashMap<String, ToolCall>();
        tools.put("list_projects", arguments -> {
            ToolArguments.allowOnly(arguments, "query", "limit");
            return lookupService.listProjects(ToolArguments.optionalText(arguments, "query"),
                    ToolArguments.optionalInt(arguments, "limit"));
        });
        tools.put("list_issue_types", arguments -> {
            ToolArguments.allowOnly(arguments, "query", "limit");
            return lookupService.listIssueTypes(ToolArguments.optionalText(arguments, "query"),
                    ToolArguments.optionalInt(arguments, "limit"));
        });
        return tools;
    }
}
