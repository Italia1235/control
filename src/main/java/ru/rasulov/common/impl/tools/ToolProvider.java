package ru.rasulov.common.impl.tools;

import java.util.Map;

/**
 * A feature's tools: the code behind the tool names of its {@code llm/*-tools.json}. Every feature area has one, as a
 * bean; {@link ToolRegistry} collects them all, so a new feature adds its provider and its tool file and {@code common}
 * (which knows no feature) does not change.
 */
public interface ToolProvider {

    /** Tool name to the code that runs it. The names are those of the tool file. */
    Map<String, ToolCall> tools();
}
