package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AssignmentVerifierTest {
    private final WorkflowManager workflows = mock(WorkflowManager.class);
    private final WorkflowSchemeManager schemes = mock(WorkflowSchemeManager.class);
    private final Project project = mock(Project.class);
    private final AssignableWorkflowScheme scheme = mock(AssignableWorkflowScheme.class);
    private final JiraWorkflow target = mock(JiraWorkflow.class);
    private final AssignmentVerifier verifier = new AssignmentVerifier(workflows, schemes);

    private Map<String, String> expected() {
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("10001", "Target");
        return expected;
    }

    private void jiraSays(String actualWorkflow, boolean active) {
        when(project.getKey()).thenReturn("ABC");
        when(schemes.getWorkflowSchemeObj(project)).thenReturn(scheme);
        when(scheme.getActualWorkflow("10001")).thenReturn(actualWorkflow);
        when(workflows.getWorkflow("Target")).thenReturn(target);
        when(workflows.isActive(target)).thenReturn(active);
    }

    @Test
    public void nothingIsReportedWhenTheMappingMatchesAndTheWorkflowIsActive() {
        jiraSays("target", true);
        assertNull(verifier.verify(project, expected()));
    }

    @Test
    public void anInactiveWorkflowOfAProjectInUseIsReported() {
        jiraSays("Target", false);
        String problem = verifier.verify(project, expected());
        assertNotNull(problem);
        assertTrue(problem, problem.contains("'Target' is reported inactive"));
        assertTrue(problem, problem.contains("nothing was repaired"));
    }

    @Test
    public void aWrongEffectiveMappingIsReported() {
        jiraSays("Old", true);
        String problem = verifier.verify(project, expected());
        assertNotNull(problem);
        assertTrue(problem, problem.contains("resolves to 'Old', expected 'Target'"));
    }

    @Test
    public void aFailedReadBackIsReportedAsNotConfirmedNotAsSuccess() {
        when(project.getKey()).thenReturn("ABC");
        when(schemes.getWorkflowSchemeObj(project)).thenThrow(new IllegalStateException("boom"));
        String problem = verifier.verify(project, expected());
        assertNotNull(problem);
        assertTrue(problem, problem.contains("not confirmed"));
    }

    @Test
    public void nothingIsReadWhenNothingIsExpected() {
        assertNull(verifier.verify(project, Collections.<String, String>emptyMap()));
    }
}
