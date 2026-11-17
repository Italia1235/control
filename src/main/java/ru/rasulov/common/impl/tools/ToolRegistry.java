package ru.rasulov.common.impl.tools;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All tools of all features by name. Two providers with the same tool name is a packaging bug and stops the start. */
@Named
public class ToolRegistry {

    private final Map<String, ToolCall> calls = new LinkedHashMap<String, ToolCall>();

    @Inject
    public ToolRegistry(List<ToolProvider> providers) {
        for (ToolProvider provider : providers) {
            for (Map.Entry<String, ToolCall> tool : provider.tools().entrySet()) {
                if (calls.put(tool.getKey(), tool.getValue()) != null) {
                    throw new IllegalStateException("Two providers offer the tool '" + tool.getKey() + "'");
                }
            }
        }
    }

    /** @return the tool, or {@code null} when no feature offers it */
    public ToolCall find(String name) {
        return calls.get(name);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(calls.keySet());
    }
}
