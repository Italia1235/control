package ru.rasulov.common.impl.tools;

import com.fasterxml.jackson.databind.JsonNode;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.rest.RequestBodies;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Reads the {@code arguments} of a tool call the way the REST adapters read a request: wrong input is an
 * {@link InvalidRequestException} (an error result for the model), never a server error. Unknown arguments are refused
 * too: a model that misspells an argument must be told, not silently ignored.
 */
public final class ToolArguments {

    private ToolArguments() {
    }

    /** The arguments as the request DTO; a bad field or an unknown one is refused with Jackson's message. */
    public static <T> T parse(JsonNode arguments, Class<T> type) {
        return RequestBodies.parse(arguments.toString(), type);
    }

    /** Refuses every argument that is not in {@code allowed}. */
    public static void allowOnly(JsonNode arguments, String... allowed) {
        Set<String> known = new HashSet<String>(Arrays.asList(allowed));
        for (Iterator<String> names = arguments.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!known.contains(name)) {
                throw new InvalidRequestException("Unknown argument '" + name + "'. This tool takes: "
                        + (known.isEmpty() ? "no arguments" : String.join(", ", new java.util.TreeSet<String>(known)))
                        + ".");
            }
        }
    }

    /** A required text argument. */
    public static String text(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            throw new InvalidRequestException("The argument '" + name + "' is required.");
        }
        if (!value.isTextual()) {
            throw new InvalidRequestException("The argument '" + name + "' must be text.");
        }
        return value.textValue();
    }

    /** An optional text argument; {@code null} when absent. */
    public static String optionalText(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidRequestException("The argument '" + name + "' must be text.");
        }
        return value.textValue();
    }

    /** An optional whole number argument; {@code null} when absent. */
    public static Integer optionalInt(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isInt()) {
            throw new InvalidRequestException("The argument '" + name + "' must be a whole number.");
        }
        return value.intValue();
    }

    /** An optional boolean argument; {@code false} when absent. */
    public static boolean optionalBoolean(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new InvalidRequestException("The argument '" + name + "' must be true or false.");
        }
        return value.booleanValue();
    }
}
