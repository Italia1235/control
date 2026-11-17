package ru.rasulov.common.rest;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.rasulov.common.api.exception.InvalidRequestException;

import java.io.IOException;

/**
 * Parses request bodies here instead of letting Jersey do it: that way exactly the parsing errors (bad JSON, unknown
 * property) become a 400, and no exception mapper for Jackson can swallow a real server error.
 */
public final class RequestBodies {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestBodies() {
    }

    /**
     * @throws InvalidRequestException the body is empty, is not JSON, or does not fit {@code type}
     */
    public static <T> T parse(String body, Class<T> type) {
        return parse(body, type, null);
    }

    /**
     * Like {@link #parse(String, Class)}; when {@code path} is given (where {@code body} sits inside the request, for
     * example {@code transitionRules}), a mapping error also names the field by its request path, for example
     * {@code transitionRules[0].addValidators[0].type}.
     *
     * @throws InvalidRequestException the body is empty, is not JSON, or does not fit {@code type}
     */
    public static <T> T parse(String body, Class<T> type, String path) {
        if (body == null || body.trim().isEmpty()) {
            throw new InvalidRequestException("The request body is empty; send a JSON object.");
        }
        try {
            return MAPPER.readValue(body, type);
        } catch (JsonMappingException e) {
            throw new InvalidRequestException("Malformed request body: " + withoutSourceLocation(e)
                    + (path == null ? "" : " (request path: " + requestPath(path, e) + ")"));
        } catch (IOException e) {
            throw new InvalidRequestException("Malformed request body: " + withoutSourceLocation(e));
        }
    }

    private static String requestPath(String path, JsonMappingException e) {
        StringBuilder result = new StringBuilder(path);
        for (JsonMappingException.Reference reference : e.getPath()) {
            if (reference.getFieldName() != null) {
                result.append('.').append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                result.append('[').append(reference.getIndex()).append(']');
            }
        }
        return result.toString();
    }

    /** Jackson appends "\n at [Source: ...]" to its messages; the reader object name is noise for the caller. */
    private static String withoutSourceLocation(IOException e) {
        String message = String.valueOf(e.getMessage());
        int location = message.indexOf("\n at [Source");
        return location >= 0 ? message.substring(0, location) : message;
    }
}
