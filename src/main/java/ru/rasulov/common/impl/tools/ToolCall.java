package ru.rasulov.common.impl.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** One tool an LLM can call: takes the arguments it sent, returns what the service answered. */
@FunctionalInterface
public interface ToolCall {

    /**
     * @param arguments the {@code arguments} object of the call; an empty object when the tool has none
     * @return an object that serializes to the answer for the model (the DTO of the service)
     * @throws ru.rasulov.common.api.exception.ServiceException the call as a whole is refused (bad arguments, no rights,
     *                                                          busy); it becomes an error result for the model
     */
    Object call(JsonNode arguments);
}
