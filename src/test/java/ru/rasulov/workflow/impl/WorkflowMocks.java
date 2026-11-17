package ru.rasulov.workflow.impl;

import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.issue.status.category.StatusCategory;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.TransitionResolution;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.resolution.Resolution;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.common.impl.Normalizer.normalize;

/** Small factories for Jira objects and requests, shared by the workflow unit tests. */
final class WorkflowMocks {

    private WorkflowMocks() {
    }

    static ConstantsManager resolutions() {
        ConstantsManager constants = mock(ConstantsManager.class);
        Resolution fixed = mock(Resolution.class);
        when(fixed.getId()).thenReturn("1");
        when(fixed.getName()).thenReturn("Fixed");
        when(fixed.getDescription()).thenReturn("Work completed");
        when(constants.getResolution("1")).thenReturn(fixed);
        return constants;
    }

    static TransitionResolution fixed() {
        return new TransitionResolution("SET", "1");
    }

    /** A Jira status of the given category key ({@code new}, {@code indeterminate}, {@code done}). */
    static Status status(String id, String name, String categoryKey) {
        Status status = mock(Status.class);
        StatusCategory category = mock(StatusCategory.class);
        when(category.getKey()).thenReturn(categoryKey);
        when(status.getId()).thenReturn(id);
        when(status.getName()).thenReturn(name);
        when(status.getStatusCategory()).thenReturn(category);
        return status;
    }

    /** Makes {@code catalog.find} behave like the real one: by name, trimmed and without case. */
    static void jiraHas(StatusCatalog catalog, Status... statuses) {
        when(catalog.find(anyString())).thenAnswer(invocation -> {
            String wanted = normalize(invocation.getArgument(0));
            for (Status status : statuses) {
                if (normalize(status.getName()).equals(wanted)) {
                    return status;
                }
            }
            return null;
        });
    }

    static StatusDefinition status(String name, String category) {
        return new StatusDefinition(name, category, null);
    }

    static TransitionDefinition move(String name, String from, String to) {
        return new TransitionDefinition(name, from, false, to, null, "done".equalsIgnoreCase(to) ? fixed() : null);
    }

    static TransitionDefinition fromAny(String name, String to) {
        return new TransitionDefinition(name, null, true, to, null, "done".equalsIgnoreCase(to) ? fixed() : null);
    }

    static WorkflowDefinition request(String name, List<StatusDefinition> statuses,
                                         String initial, List<TransitionDefinition> transitions) {
        java.util.ArrayList<TransitionDefinition> resolved = new java.util.ArrayList<TransitionDefinition>();
        for (TransitionDefinition transition : transitions) {
            if (transition == null) {
                resolved.add(null);
                continue;
            }
            boolean done = false;
            for (StatusDefinition status : statuses) {
                if (normalize(status.getName()).equals(normalize(transition.getTo()))) {
                    done = "DONE".equals(status.getCategory()) || "done".equalsIgnoreCase(status.getName());
                }
            }
            resolved.add(done && transition.getResolution() == null
                    ? new TransitionDefinition(transition.getName(), transition.getFrom(), transition.isFromAny(),
                            transition.getTo(), transition.getScreen(), fixed()) : transition);
        }
        return new WorkflowDefinition(name, null, statuses, initial, resolved);
    }

    /** Open -> In Progress -> Done, and Done -> Open ("Reopen"): the workflow most tests start from. */
    static WorkflowDefinition simple() {
        return request("Bug flow",
                Arrays.asList(status("Open", "TO_DO"), status("In Progress", "IN_PROGRESS"), status("Done", "DONE")),
                "Open",
                Arrays.asList(move("Start", "Open", "In Progress"), move("Finish", "In Progress", "Done"),
                        move("Reopen", "Done", "Open")));
    }
}
