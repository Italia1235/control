package ru.rasulov.common.impl.undo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;

import java.io.IOException;

public final class JournalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    private JournalJson() { }

    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalArgumentException("Cannot serialize durable undo state", e); }
    }

    public static <T> T read(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (IOException e) { throw new IllegalArgumentException("Cannot read durable undo state", e); }
    }
}
