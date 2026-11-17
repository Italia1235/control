package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.assign.AssignMocks.mapping;

/** Swapping workflows for their copies in a scheme, and nothing else. */
public class SchemeEditTest {

    /** A builder that keeps what it is given, like Jira's. */
    private static AssignableWorkflowScheme.Builder builderOf(String defaultWorkflow, Map<String, String> mappings) {
        final Map<String, String> state = new HashMap<String, String>(mappings);
        final String[] def = {defaultWorkflow};
        AssignableWorkflowScheme.Builder builder = mock(AssignableWorkflowScheme.Builder.class);
        when(builder.getDefaultWorkflow()).thenAnswer(invocation -> def[0]);
        when(builder.getMappings()).thenAnswer(invocation -> state);
        when(builder.setDefaultWorkflow(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            def[0] = invocation.getArgument(0);
            return builder;
        });
        when(builder.setMapping(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    state.put(invocation.getArgument(0), invocation.getArgument(1));
                    return builder;
                });
        return builder;
    }

    @Test
    public void everyMappingAndTheDefaultThatNamesAReplacedWorkflowNamesItsCopyAndTheRestStays() {
        Map<String, String> copies = new HashMap<String, String>();
        copies.put("Flow A", "Flow A v2");
        copies.put("Bug flow", "Bug flow v2");
        AssignableWorkflowScheme.Builder builder = builderOf("Flow A",
                mapping("10001", "Bug flow", "10002", "Kept flow", "10003", "Bug flow"));

        SchemeEdit.replacing(copies, "Flow A").apply(builder);

        assertEquals("Flow A v2", builder.getDefaultWorkflow());
        assertEquals("Bug flow v2", builder.getMappings().get("10001"));
        assertEquals("a workflow that gets no copy stays", "Kept flow", builder.getMappings().get("10002"));
        assertEquals("Bug flow v2", builder.getMappings().get("10003"));
    }

    @Test
    public void theSystemDefaultSchemeHasNoConfiguredDefaultSoTheOneItReallyUsesIsReplaced() {
        AssignableWorkflowScheme.Builder builder = builderOf(null, new HashMap<String, String>());

        SchemeEdit.replacing(java.util.Collections.singletonMap("jira", "jira v2"), "jira").apply(builder);

        assertEquals("jira v2", builder.getDefaultWorkflow());
    }

    @Test
    public void aSchemeOnlyCopyReplacesNothing() {
        AssignableWorkflowScheme.Builder builder = builderOf("Flow A", mapping("10001", "Bug flow"));

        SchemeEdit.replacing(java.util.Collections.<String, String>emptyMap(), "Flow A").apply(builder);

        assertEquals("Flow A", builder.getDefaultWorkflow());
        assertEquals("Bug flow", builder.getMappings().get("10001"));
    }
}
