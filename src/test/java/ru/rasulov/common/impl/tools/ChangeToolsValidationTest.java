package ru.rasulov.common.impl.tools;

import com.atlassian.jira.user.ApplicationUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.undo.ChangeJournal;

import java.util.Map;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ChangeToolsValidationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ChangeJournal journal;
    private Map<String, ToolCall> tools;

    @Before
    public void setUp() {
        journal = mock(ChangeJournal.class);
        AdministratorGuard guard = mock(AdministratorGuard.class);
        ApplicationUser actor = mock(ApplicationUser.class);
        when(actor.getKey()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(actor);
        tools = new ChangeTools(journal, guard, mock(OperationRunner.class),
                mock(ru.rasulov.common.impl.ActionPlanStore.class), mock(ru.rasulov.common.impl.applies.ApplyJournal.class)).tools();
    }

    @Test
    public void ambiguousRollbackScopeCannotReachTheJournal() throws Exception {
        refuses("rollback_changes", "{\"dryRun\":true,\"operationId\":\"op\",\"sessionId\":\"session\"}");
        verifyNoInteractions(journal);
    }

    @Test
    public void scopeCannotChangeBetweenPreviewAndApply() throws Exception {
        refuses("rollback_changes", "{\"dryRun\":false,\"planId\":\"plan\",\"changeSetId\":\"different-set\"}");
        verifyNoInteractions(journal);
    }

    @Test
    public void cancellationRequiresExplicitStopVersusUndoChoice() throws Exception {
        refuses("cancel_operation", "{\"operationId\":\"op\"}");
        refuses("cancel_operation", "{\"operationId\":\"op\",\"rollback\":\"false\"}");
        verifyNoInteractions(journal);
    }

    private void refuses(String tool, String arguments) throws Exception {
        try {
            tools.get(tool).call(MAPPER.readTree(arguments));
            fail("Ambiguous or altered undo request accepted");
        } catch (InvalidRequestException expected) {
            // Validation must fail before any persisted selection or mutation.
        }
    }
}
