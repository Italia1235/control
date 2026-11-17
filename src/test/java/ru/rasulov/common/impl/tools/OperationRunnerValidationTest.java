package ru.rasulov.common.impl.tools;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.undo.ChangeJournal;

import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unsafe submission shapes must fail before creating durable work or consuming a preview. */
public class OperationRunnerValidationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ChangeJournal journal;
    private OperationRunner runner;

    @Before
    public void setUp() {
        journal = mock(ChangeJournal.class);
        AdministratorGuard guard = mock(AdministratorGuard.class);
        ApplicationUser actor = mock(ApplicationUser.class);
        when(actor.getKey()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(actor);
        when(journal.withPlanStart(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        when(journal.findOperationByPlan("plan", "admin")).thenReturn(null);
        ru.rasulov.common.impl.ActionPlanStore plans = mock(ru.rasulov.common.impl.ActionPlanStore.class);
        when(plans.peekRequest("assign_workflow", "admin", "plan")).thenReturn("{}");
        ToolProvider domain = () -> Collections.singletonMap("assign_workflow", arguments -> {
            throw new AssertionError("An invalid submission reached Jira");
        });
        ToolRegistry registry = new ToolRegistry(Collections.singletonList(domain));
        runner = new OperationRunner(journal, guard, () -> registry, new ToolCatalog(mock(PluginAccessor.class)),
                mock(JiraAuthenticationContext.class), mock(JiraThreadLocalUtil.class),
                plans, mock(ru.rasulov.common.impl.applies.ApplyJournal.class));
    }

    @After
    public void tearDown() {
        runner.destroy();
    }

    @Test
    public void managementCommandsCannotRecursivelySubmitWork() throws Exception {
        refuses("rollback_changes", "{\"dryRun\":false,\"planId\":\"plan\"}", "mutation tool");
        refuses("start_operation", "{\"dryRun\":false,\"planId\":\"plan\"}", "mutation tool");
        refuses("get_project_workflows", "{\"dryRun\":false,\"planId\":\"plan\"}", "mutation tool");
        verifyNoInteractions(journal);
    }

    @Test
    public void previewAndChangedApplyDetailsCannotBeQueued() throws Exception {
        refuses("assign_workflow", "{\"dryRun\":true,\"planId\":\"plan\"}", "dryRun=false");
        refuses("assign_workflow", "{\"dryRun\":false,\"planId\":\"plan\",\"project\":\"PROP\"}", "Unknown argument");
        refuses("assign_workflow", "{\"dryRun\":false,\"planId\":\"  \"}", "nonempty planId");
        verifyNoInteractions(journal);
    }

    @Test
    public void stoppedExecutorRefusesWithoutCreatingAnOperation() throws Exception {
        runner.destroy();
        try {
            runner.start("assign_workflow", MAPPER.readTree("{\"dryRun\":false,\"planId\":\"plan\"}"), "session", "set");
            fail("Stopped executor must not accept a confirmed write");
        } catch (ru.rasulov.common.api.exception.BusyException expected) {
            org.mockito.Mockito.verify(journal, org.mockito.Mockito.never()).openOperation(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test
    public void incompleteOrBlankGroupIdentityCannotCreateAnOperation() throws Exception {
        for (String[] identity : new String[][] {{"session", null}, {null, "set"}, {"", ""}}) {
            try {
                runner.start("assign_workflow", MAPPER.readTree("{\"dryRun\":false,\"planId\":\"plan\"}"),
                        identity[0], identity[1]);
                fail("Incomplete group identity must not start work");
            } catch (InvalidRequestException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("Supply both"));
            }
        }
        verifyNoInteractions(journal);
    }

    @Test
    public void stoppedExecutorDoesNotCreateInternalSessionOrGroup() throws Exception {
        runner.destroy();
        try {
            runner.start("assign_workflow", MAPPER.readTree("{\"dryRun\":false,\"planId\":\"plan\"}"), null, null);
            fail("Stopped executor must refuse before creating an internal group");
        } catch (ru.rasulov.common.api.exception.BusyException expected) {
            org.mockito.Mockito.verify(journal, org.mockito.Mockito.never()).createSession(org.mockito.ArgumentMatchers.anyString());
            org.mockito.Mockito.verify(journal, org.mockito.Mockito.never()).createRunnerChangeSet(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        }
    }

    private void refuses(String tool, String arguments, String reason) throws Exception {
        try {
            runner.start(tool, MAPPER.readTree(arguments), "session", "set");
            fail("Unsafe submission accepted");
        } catch (InvalidRequestException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(reason));
        }
    }
}
