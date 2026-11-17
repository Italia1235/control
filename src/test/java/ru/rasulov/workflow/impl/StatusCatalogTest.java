package ru.rasulov.workflow.impl;

import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.config.StatusCategoryManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.status.Status;
import org.junit.Test;
import ru.rasulov.common.api.LookupLimits;
import ru.rasulov.workflow.api.dto.StatusListResponse;
import ru.rasulov.workflow.api.dto.StatusLookupEntry;
import ru.rasulov.workflow.api.dto.StatusLookupResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StatusCatalogTest {

    @Test
    public void searchIsFilteredAndBoundedButReportsEveryMatch() {
        StatusManager manager = mock(StatusManager.class);
        List<Status> many = new ArrayList<Status>();
        for (int i = 0; i < 300; i++) {
            many.add(status(String.valueOf(10000 + i), String.format("Status %03d", i), i == 290 ? repeat('x', 500) : null));
        }
        when(manager.getStatuses()).thenReturn(many);
        StatusCatalog catalog = new StatusCatalog(manager, mock(StatusService.class), mock(StatusCategoryManager.class));

        StatusListResponse byDefault = catalog.list(null, null);
        StatusListResponse filtered = catalog.list("status 29", 3);
        StatusListResponse tooMany = catalog.list(null, 100000);

        assertEquals(300, byDefault.getTotal());
        assertEquals(LookupLimits.DEFAULT_REFERENCE_LIST_SIZE, byDefault.getStatuses().size());
        assertEquals(10, filtered.getTotal());
        assertEquals(3, filtered.getStatuses().size());
        assertEquals("Status 290", filtered.getStatuses().get(0).getName());
        assertEquals(200, filtered.getStatuses().get(0).getDescription().length());
        assertEquals(LookupLimits.MAX_REFERENCE_LIST_SIZE, tooMany.getStatuses().size());
    }

    @Test
    public void readBackByIdAsksJiraForThatExactIdAndNeverScansTheCatalog() {
        StatusManager manager = mock(StatusManager.class);
        Status created = status("10042", "Review", null);
        when(manager.getStatus("10042")).thenReturn(created);
        StatusCatalog catalog = new StatusCatalog(manager, mock(StatusService.class), mock(StatusCategoryManager.class));

        assertSame(created, catalog.findById("10042"));
        assertNull(catalog.findById("10043"));
        verify(manager, never()).getStatuses();
    }

    @Test
    public void lookupAnswersEveryNameInRequestOrderFromOneCatalogRead() {
        Status doneStatus = status("10002", "Done", repeat('d', 500));
        Status reviewStatus = status("10001", "In Review", "being reviewed");
        StatusManager manager = mock(StatusManager.class);
        when(manager.getStatuses()).thenReturn(Arrays.asList(doneStatus, reviewStatus));
        StatusCatalog catalog = new StatusCatalog(manager, mock(StatusService.class), mock(StatusCategoryManager.class));

        StatusLookupResponse response = catalog.lookup(Arrays.asList("in review ", "Missing", " DONE"));

        assertEquals(3, response.getLookup().size());
        StatusLookupEntry review = response.getLookup().get(0);
        assertEquals("in review ", review.getName());
        assertEquals(StatusLookupEntry.Result.FOUND, review.getResult());
        assertEquals(1, review.getStatuses().size());
        assertEquals("10001", review.getStatuses().get(0).getId());
        assertEquals("In Review", review.getStatuses().get(0).getName());
        assertEquals("UNDEFINED", review.getStatuses().get(0).getCategory());
        assertEquals("being reviewed", review.getStatuses().get(0).getDescription());
        StatusLookupEntry missing = response.getLookup().get(1);
        assertEquals("Missing", missing.getName());
        assertEquals(StatusLookupEntry.Result.NOT_FOUND, missing.getResult());
        assertTrue(missing.getStatuses().isEmpty());
        StatusLookupEntry done = response.getLookup().get(2);
        assertEquals(" DONE", done.getName());
        assertEquals(StatusLookupEntry.Result.FOUND, done.getResult());
        assertEquals(200, done.getStatuses().get(0).getDescription().length());
        verify(manager, times(1)).getStatuses();
    }

    @Test
    public void lookupReportsEveryStatusOfAnAmbiguousNameOrderedById() {
        Status newer = status("10020", "review", null);
        Status older = status("10010", " Review ", null);
        Status other = status("10030", "Other", null);
        StatusManager manager = mock(StatusManager.class);
        when(manager.getStatuses()).thenReturn(Arrays.asList(newer, older, other));
        StatusCatalog catalog = new StatusCatalog(manager, mock(StatusService.class), mock(StatusCategoryManager.class));

        StatusLookupResponse response = catalog.lookup(Arrays.asList("  REVIEW", "Review"));

        for (StatusLookupEntry entry : response.getLookup()) {
            assertEquals(StatusLookupEntry.Result.AMBIGUOUS, entry.getResult());
            assertEquals(2, entry.getStatuses().size());
            assertEquals("10010", entry.getStatuses().get(0).getId());
            assertEquals("10020", entry.getStatuses().get(1).getId());
        }
        assertEquals("  REVIEW", response.getLookup().get(0).getName());
        assertEquals("Review", response.getLookup().get(1).getName());
    }

    @Test
    public void lookupGivesAnEntryForEachDuplicateInTheRequest() {
        Status done = status("10001", "Done", null);
        StatusManager manager = mock(StatusManager.class);
        when(manager.getStatuses()).thenReturn(Arrays.asList(done));
        StatusCatalog catalog = new StatusCatalog(manager, mock(StatusService.class), mock(StatusCategoryManager.class));

        StatusLookupResponse response = catalog.lookup(Arrays.asList("Done", "done", "Done"));

        assertEquals(3, response.getLookup().size());
        for (StatusLookupEntry entry : response.getLookup()) {
            assertEquals(StatusLookupEntry.Result.FOUND, entry.getResult());
            assertEquals("10001", entry.getStatuses().get(0).getId());
        }
    }

    private static Status status(String id, String name, String description) {
        Status status = mock(Status.class);
        when(status.getId()).thenReturn(id);
        when(status.getName()).thenReturn(name);
        when(status.getDescription()).thenReturn(description);
        return status;
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
