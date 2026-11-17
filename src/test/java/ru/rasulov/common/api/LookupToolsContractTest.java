package ru.rasulov.common.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Keeps {@code llm/common-tools.json} and {@link LookupLimits} from drifting apart. */
public class LookupToolsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void projectToolTakesTheQueryAndALimitCappedLikeTheCode() throws Exception {
        JsonNode projects = tool("list_projects").get("input_schema");

        assertEquals(new TreeSet<String>(Arrays.asList("query", "limit")), names(projects.get("properties")));
        assertEquals(LookupLimits.MAX_PROJECT_LIST_SIZE,
                projects.get("properties").get("limit").get("maximum").intValue());
    }

    @Test
    public void issueTypeToolTakesTheQueryAndABoundedLimit() throws Exception {
        JsonNode issueTypes = tool("list_issue_types").get("input_schema");

        assertEquals(new TreeSet<String>(Arrays.asList("query", "limit")), names(issueTypes.get("properties")));
        assertEquals(LookupLimits.MAX_REFERENCE_LIST_SIZE,
                issueTypes.get("properties").get("limit").get("maximum").intValue());
    }

    private static JsonNode tool(String name) throws Exception {
        InputStream in = LookupToolsContractTest.class.getResourceAsStream("/llm/common-tools.json");
        assertNotNull("llm/common-tools.json is missing from resources", in);
        try {
            for (JsonNode tool : MAPPER.readTree(in)) {
                if (name.equals(tool.get("name").textValue())) {
                    return tool;
                }
            }
        } finally {
            in.close();
        }
        throw new AssertionError("tool not described: " + name);
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new TreeSet<String>();
        for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
