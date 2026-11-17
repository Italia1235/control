package ru.rasulov.workflow.impl;

import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.config.StatusCategoryManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.status.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.workflow.api.StatusUpdater;
import ru.rasulov.workflow.api.WorkflowAssigner;
import ru.rasulov.workflow.api.WorkflowCloner;
import ru.rasulov.workflow.api.WorkflowConditionReader;
import ru.rasulov.workflow.api.WorkflowConfigurer;
import ru.rasulov.workflow.api.WorkflowCopier;
import ru.rasulov.workflow.api.WorkflowDraftPublisher;
import ru.rasulov.workflow.api.WorkflowReader;
import ru.rasulov.workflow.api.WorkflowRenamer;
import ru.rasulov.workflow.api.WorkflowValidatorReader;
import ru.rasulov.workflow.api.dto.StatusListResponse;
import ru.rasulov.workflow.api.dto.StatusLookupEntry;
import ru.rasulov.workflow.api.dto.StatusLookupResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code list_statuses} with {@code names}: arguments are refused before the catalog is read, answers per name. */
public class ListStatusesNamesToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StatusManager manager = mock(StatusManager.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private ToolCall listStatuses;

    @Before
    public void setUp() {
        Status done = status("10001", "Done");
        when(manager.getStatuses()).thenReturn(Arrays.asList(done));
        StatusCatalog catalog = new StatusCatalog(manager, mock(StatusService.class), mock(StatusCategoryManager.class));
        listStatuses = new WorkflowTools(mock(WorkflowConfigurer.class), new WorkflowStatusReaderImpl(guard, catalog),
                mock(WorkflowAssigner.class), mock(WorkflowCloner.class), mock(WorkflowDraftPublisher.class),
                mock(WorkflowReader.class), mock(WorkflowRenamer.class), mock(StatusUpdater.class),
                mock(WorkflowCopier.class), mock(WorkflowValidatorReader.class), mock(WorkflowConditionReader.class))
                .tools().get("list_statuses");
    }

    @Test
    public void namesAreAnsweredAsALookupInRequestOrder() throws Exception {
        StatusLookupResponse response = (StatusLookupResponse) listStatuses.call(
                MAPPER.readTree("{\"names\":[\"done\",\"Nope\",\" Done \"]}"));

        assertEquals(3, response.getLookup().size());
        assertEquals("done", response.getLookup().get(0).getName());
        assertEquals(StatusLookupEntry.Result.FOUND, response.getLookup().get(0).getResult());
        assertEquals(StatusLookupEntry.Result.NOT_FOUND, response.getLookup().get(1).getResult());
        assertEquals(" Done ", response.getLookup().get(2).getName());
        assertEquals("10001", response.getLookup().get(2).getStatuses().get(0).getId());
        verify(manager, times(1)).getStatuses();
        verify(guard).requireAdministrator();
    }

    @Test
    public void nullQueryAndLimitNextToNamesCountAsAbsent() throws Exception {
        Object answer = listStatuses.call(MAPPER.readTree("{\"names\":[\"Done\"],\"query\":null,\"limit\":null}"));

        assertTrue(answer instanceof StatusLookupResponse);
    }

    @Test
    public void aNameOfTheLimitLengthAfterTrimAndThirtyNamesAreAccepted() throws Exception {
        List<String> thirty = new ArrayList<String>();
        for (int i = 0; i < 30; i++) {
            thirty.add("\"" + (i == 0 ? "  " + repeat(60) + "  " : "n" + i) + "\"");
        }

        StatusLookupResponse response = (StatusLookupResponse) listStatuses.call(
                MAPPER.readTree("{\"names\":[" + String.join(",", thirty) + "]}"));

        assertEquals(30, response.getLookup().size());
    }

    @Test
    public void namesTogetherWithAQueryOrLimitAreRefusedEvenWhenEmptyOrZero() throws Exception {
        for (String extra : Arrays.asList("\"query\":\"Do\"", "\"query\":\"\"", "\"limit\":5", "\"limit\":0")) {
            refused("{\"names\":[\"Done\"]," + extra + "}", "names");
        }
    }

    @Test
    public void unusableNamesAreRefusedBeforeTheCatalogIsRead() throws Exception {
        List<String> thirtyOne = new ArrayList<String>();
        for (int i = 0; i < 31; i++) {
            thirtyOne.add("\"s" + i + "\"");
        }
        for (String names : Arrays.asList("null", "[]", "[" + String.join(",", thirtyOne) + "]", "[null]",
                "[\"Done\",null]", "[1]", "[true]", "[[\"Done\"]]", "[\"\"]", "[\"   \"]", "\"Done\"",
                "[\"" + repeat(61) + "\"]", "[\"  " + repeat(61) + "  \"]")) {
            refused("{\"names\":" + names + "}", "names");
        }
        verify(manager, never()).getStatuses();
    }

    @Test
    public void aNonAdministratorNeverReachesTheCatalog() throws Exception {
        doThrow(new IllegalStateException("not an administrator")).when(guard).requireAdministrator();

        try {
            listStatuses.call(MAPPER.readTree("{\"names\":[\"Done\"]}"));
            fail("expected the guard to refuse");
        } catch (IllegalStateException expected) {
            assertEquals("not an administrator", expected.getMessage());
        }
        verify(manager, never()).getStatuses();
    }

    @Test
    public void withoutNamesTheQueryAnswerIsUnchanged() throws Exception {
        Object answer = listStatuses.call(MAPPER.readTree("{\"query\":\"don\",\"limit\":5}"));

        StatusListResponse list = (StatusListResponse) answer;
        assertEquals(1, list.getTotal());
        assertEquals("Done", list.getStatuses().get(0).getName());
    }

    private void refused(String arguments, String mention) throws Exception {
        JsonNode node = MAPPER.readTree(arguments);
        try {
            listStatuses.call(node);
        } catch (InvalidRequestException e) {
            assertTrue(arguments + " -> " + e.getMessage(),
                    e.getMessage().contains(mention) && !e.getMessage().startsWith("Unknown argument"));
            return;
        }
        fail("expected a refusal of " + arguments);
    }

    private static Status status(String id, String name) {
        Status status = mock(Status.class);
        when(status.getId()).thenReturn(id);
        when(status.getName()).thenReturn(name);
        return status;
    }

    private static String repeat(int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, 'x');
        return new String(chars);
    }
}
