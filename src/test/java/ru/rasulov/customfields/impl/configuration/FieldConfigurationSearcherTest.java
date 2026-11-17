package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.Invocation;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse.Item;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse.Kind;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse.UsageStatus;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Search by id and name over configurations and schemes: usage, standalone objects, DEFAULT, paging, unreadable usage. */
public class FieldConfigurationSearcherTest {
    private FieldLayoutFake jira;
    private ProjectManager projectManager;
    private FieldConfigurationSearcher searcher;

    @Before
    public void setUp() {
        jira = new FieldLayoutFake();
        projectManager = mock(ProjectManager.class);
        when(projectManager.getProjects()).thenAnswer(call -> new ArrayList<Project>(jira.allProjects));
        searcher = new FieldConfigurationSearcher(jira.guard, jira.catalog, jira.manager, projectManager);
    }

    @Test
    public void configurationByIdShowsEverySchemeRowPointingAtItAndNoSchemeForAStandaloneOne() {
        jira.layout(10L, "Shared");
        jira.layout(11L, "Standalone");
        jira.scheme(20L, "PAY scheme", null);
        jira.scheme(21L, "OPS scheme", null);
        jira.rows.get(20L).put(null, null);
        jira.rows.get(20L).put("1", 10L);
        jira.rows.get(20L).put("2", 10L);
        jira.rows.get(21L).put("3", 10L);

        Item shared = only(search(Kind.CONFIGURATION, "10", null, 0, 1));
        assertEquals(Long.valueOf(10L), shared.getFieldConfiguration().getId());
        assertEquals("Shared", shared.getFieldConfiguration().getName());
        assertNull(shared.getScheme());
        assertEquals(UsageStatus.READ, shared.getUsage().getStatus());
        assertFalse(shared.getUsage().isTruncated());
        assertEquals(3, shared.getUsage().getTotal().intValue());
        assertEquals(2, shared.getUsage().getSchemesTotal().intValue());
        assertEquals("OPS scheme", shared.getUsage().getLinks().get(0).getScheme().getName());
        assertEquals("3", shared.getUsage().getLinks().get(0).getIssueTypeId());
        assertEquals("PAY scheme", shared.getUsage().getLinks().get(1).getScheme().getName());
        assertEquals("1", shared.getUsage().getLinks().get(1).getIssueTypeId());
        assertEquals("2", shared.getUsage().getLinks().get(2).getIssueTypeId());
        assertFalse(shared.getUsage().getLinks().get(1).isDefaultRow());
        assertFalse(shared.getUsage().getLinks().get(1).isFallback());

        Item standalone = only(search(Kind.CONFIGURATION, "11", null, 0, 1));
        assertEquals("a verified empty usage, not an unknown one", UsageStatus.READ, standalone.getUsage().getStatus());
        assertEquals(0, standalone.getUsage().getTotal().intValue());
        assertFalse(standalone.getUsage().isTruncated());
        assertTrue(standalone.getUsage().getLinks().isEmpty());
        assertTrue(search(Kind.CONFIGURATION, "11", null, 0, 1).isUsageComplete());
    }

    @Test
    public void defaultConfigurationHasNoIdAndIsUsedByTheImplicitSchemeAndDefaultRows() {
        jira.scheme(20L, "PAY scheme", null);
        jira.rows.get(20L).put(null, null);
        jira.rows.get(20L).put("1", null);

        Item item = only(search(Kind.CONFIGURATION, "DEFAULT", null, 0, 1));

        assertNull(item.getFieldConfiguration().getId());
        assertTrue(item.getFieldConfiguration().isDefaultConfiguration());
        assertEquals(3, item.getUsage().getTotal().intValue());
        assertEquals(2, item.getUsage().getSchemesTotal().intValue());
        assertTrue("the implicit Default scheme first by name", item.getUsage().getLinks().get(0).getScheme().isDefaultScheme());
        assertNull(item.getUsage().getLinks().get(0).getScheme().getId());
        assertTrue(item.getUsage().getLinks().get(0).isDefaultRow());
        assertFalse("a physical row", item.getUsage().getLinks().get(1).isFallback());
        assertEquals("PAY scheme", item.getUsage().getLinks().get(1).getScheme().getName());
        assertTrue(item.getUsage().getLinks().get(1).isDefaultRow());
        assertEquals("1", item.getUsage().getLinks().get(2).getIssueTypeId());
    }

    @Test
    public void aSchemeWithoutADefaultRowReachesTheDefaultConfigurationByFallbackNotByARow() {
        jira.layout(10L, "Bug fields");
        jira.scheme(20L, "No DEFAULT row", null);
        jira.rows.get(20L).put("2", 10L);

        Item defaults = only(search(Kind.CONFIGURATION, "DEFAULT", null, 0, 1));
        assertEquals("the implicit scheme and the fallback of the scheme without a DEFAULT row", 2, defaults.getUsage().getTotal().intValue());
        LinkViewAccess fallback = new LinkViewAccess(defaults.getUsage().getLinks().get(1));
        assertEquals("No DEFAULT row", fallback.scheme());
        assertTrue(fallback.link.isFallback());
        assertTrue(fallback.link.isDefaultRow());
        assertNull("no physical row to point at", fallback.link.getIssueTypeId());

        Item own = only(search(Kind.CONFIGURATION, "10", null, 0, 1));
        assertEquals(1, own.getUsage().getTotal().intValue());
        assertFalse("a physical Bug row is not a fallback", own.getUsage().getLinks().get(0).isFallback());
        assertEquals("2", own.getUsage().getLinks().get(0).getIssueTypeId());

        jira.rows.get(20L).put(null, null);
        Item withRow = only(search(Kind.CONFIGURATION, "DEFAULT", null, 0, 1));
        assertEquals("a physical DEFAULT row replaces the fallback", 2, withRow.getUsage().getTotal().intValue());
        assertFalse(withRow.getUsage().getLinks().get(1).isFallback());
    }

    @Test
    public void usageBeyondThePageIsTruncatedNotCompleteAndTheRestCanBeReadAtTheBoundaries() {
        jira.layout(10L, "Shared");
        for (long id = 100; id < 121; id++) {                       // 21 schemes, one row each
            jira.scheme(id, String.format("Scheme %03d", id), null);
            jira.rows.get(id).put("1", 10L);
        }
        FieldConfigurationSearchResponse first = search(Kind.CONFIGURATION, "10", null, 0, 1);
        Item item = only(first);
        assertEquals(UsageStatus.READ, item.getUsage().getStatus());
        assertEquals(21, item.getUsage().getTotal().intValue());
        assertEquals(21, item.getUsage().getSchemesTotal().intValue());
        assertEquals(20, item.getUsage().getLinks().size());
        assertTrue(item.getUsage().isTruncated());
        assertEquals(20, item.getUsage().getNextUsageOffset().intValue());
        assertFalse("read successfully but not all included", first.isUsageComplete());

        FieldConfigurationSearchResponse last = searcher.search(Kind.CONFIGURATION, "10", null, 0, 1, 20, 20);
        Item rest = only(last);
        assertEquals(1, rest.getUsage().getLinks().size());
        assertEquals(Long.valueOf(120L), rest.getUsage().getLinks().get(0).getScheme().getId());
        assertFalse(rest.getUsage().isTruncated());
        assertTrue(last.isUsageComplete());

        jira.layout(11L, "Rows");
        jira.scheme(200L, "Many rows", null);
        for (int type = 1; type <= 51; type++) jira.rows.get(200L).put(String.valueOf(1000 + type), 11L);
        List<String> seen = new ArrayList<String>();
        int at = 0;
        while (true) {
            Item page = only(searcher.search(Kind.CONFIGURATION, "11", null, 0, 1, at, 50));
            assertEquals(51, page.getUsage().getTotal().intValue());
            assertEquals(1, page.getUsage().getSchemesTotal().intValue());
            for (ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse.LinkView link : page.getUsage().getLinks()) {
                seen.add(link.getIssueTypeId());
            }
            if (page.getUsage().getNextUsageOffset() == null) break;
            assertTrue(page.getUsage().isTruncated());
            at = page.getUsage().getNextUsageOffset();
        }
        assertEquals(51, seen.size());
        assertEquals("no repeats", 51, new java.util.HashSet<String>(seen).size());
    }

    @Test
    public void projectsOfASchemeAreTruncatedAndPagedLikeLinks() {
        jira.scheme(20L, "Wide", null);
        for (int i = 1; i <= 21; i++) {
            Project project = jira.project(i, String.format("P%02d", i));
            jira.associations.put(project.getId(), 20L);
        }
        Item item = only(search(Kind.SCHEME, "20", null, 0, 1));
        assertEquals(21, item.getUsage().getTotal().intValue());
        assertEquals(20, item.getUsage().getProjects().size());
        assertTrue(item.getUsage().isTruncated());
        Item rest = only(searcher.search(Kind.SCHEME, "20", null, 0, 1, item.getUsage().getNextUsageOffset(), 20));
        assertEquals(1, rest.getUsage().getProjects().size());
        assertEquals("P21", rest.getUsage().getProjects().get(0).getKey());
        assertFalse(rest.getUsage().isTruncated());
    }

    @Test
    public void missingIdIsFailedWithAnErrorCodeAndNameWithoutAMatchIsAnEmptyOk() {
        FieldConfigurationSearchResponse missing = search(Kind.CONFIGURATION, "999", null, 0, 1);
        assertEquals(FieldConfigurationSearchResponse.Status.FAILED, missing.getStatus());
        assertEquals(ErrorCode.UNKNOWN_FIELD_CONFIGURATION, missing.getErrorCode());
        assertEquals(ErrorCode.UNKNOWN_SCHEME, search(Kind.SCHEME, "999", null, 0, 1).getErrorCode());

        FieldConfigurationSearchResponse none = search(Kind.CONFIGURATION, null, "nothing like this", 0, 20);
        assertEquals(FieldConfigurationSearchResponse.Status.OK, none.getStatus());
        assertEquals(0, none.getTotal());
        assertTrue(none.getItems().isEmpty());
        assertNull(none.getNextOffset());
    }

    @Test
    public void sameNamesAreSeparateCandidatesWithTheirOwnIds() {
        jira.layout(10L, "SD fields");
        jira.layout(11L, "sd FIELDS");
        jira.layout(12L, "Other");
        jira.scheme(20L, "SD fields", null);
        jira.scheme(21L, "SD fields", null);
        jira.rows.get(21L).put("1", 11L);

        FieldConfigurationSearchResponse configurations = search(Kind.CONFIGURATION, null, "sd fields", 0, 20);
        assertEquals(2, configurations.getTotal());
        assertEquals(Long.valueOf(10L), configurations.getItems().get(0).getFieldConfiguration().getId());
        assertEquals(Long.valueOf(11L), configurations.getItems().get(1).getFieldConfiguration().getId());
        assertEquals(0, configurations.getItems().get(0).getUsage().getTotal().intValue());
        assertEquals(1, configurations.getItems().get(1).getUsage().getTotal().intValue());
        assertEquals(Long.valueOf(21L), configurations.getItems().get(1).getUsage().getLinks().get(0).getScheme().getId());

        FieldConfigurationSearchResponse schemes = search(Kind.SCHEME, null, "SD FIELDS", 0, 20);
        assertEquals(2, schemes.getTotal());
        assertEquals(Long.valueOf(20L), schemes.getItems().get(0).getScheme().getId());
        assertEquals(Long.valueOf(21L), schemes.getItems().get(1).getScheme().getId());
    }

    @Test
    public void pagingByNameNeverSkipsOrRepeatsAnObjectOfAnUnchangedSet() {
        for (long id = 10; id < 17; id++) jira.layout(id, "Page " + (id % 3 == 0 ? "b" : "a"));
        List<Long> seen = new ArrayList<Long>();
        int offset = 0;
        int pages = 0;
        while (true) {
            FieldConfigurationSearchResponse page = search(Kind.CONFIGURATION, null, "page", offset, 3);
            assertEquals(7, page.getTotal());
            assertEquals(offset, page.getOffset());
            for (Item item : page.getItems()) seen.add(item.getFieldConfiguration().getId());
            pages++;
            if (page.getNextOffset() == null) break;
            assertEquals(offset + 3, page.getNextOffset().intValue());
            offset = page.getNextOffset();
        }
        assertEquals(3, pages);
        assertEquals(7, seen.size());
        assertEquals("no repeats", 7, new java.util.HashSet<Long>(seen).size());
        assertEquals("sorted by name, then id", java.util.Arrays.asList(10L, 11L, 13L, 14L, 16L, 12L, 15L), seen);
        assertTrue("an offset past the end is an empty last page", search(Kind.CONFIGURATION, null, "page", 50, 3)
                .getItems().isEmpty());
    }

    @Test
    public void schemeShowsProjectsAndTheImplicitDefaultSchemeShowsProjectsWithoutOne() {
        jira.scheme(20L, "PAY scheme", null);
        Project pay = jira.project(1, "PAY");
        Project ops = jira.project(2, "OPS");
        jira.project(3, "LAB");
        jira.associations.put(pay.getId(), 20L);
        jira.associations.put(ops.getId(), 20L);

        Item explicit = only(search(Kind.SCHEME, "20", null, 0, 1));
        assertEquals(UsageStatus.READ, explicit.getUsage().getStatus());
        assertEquals(2, explicit.getUsage().getTotal().intValue());
        assertEquals("OPS", explicit.getUsage().getProjects().get(0).getKey());
        assertNull(explicit.getUsage().getLinks());

        Item implicit = only(search(Kind.SCHEME, "DEFAULT", null, 0, 1));
        assertNull(implicit.getScheme().getId());
        assertTrue(implicit.getScheme().isDefaultScheme());
        assertEquals(1, implicit.getUsage().getTotal().intValue());
        assertEquals("LAB", implicit.getUsage().getProjects().get(0).getKey());
    }

    @Test
    public void unreadableUsageIsUnavailableNotEmptyAndTheObjectIsStillReturned() {
        jira.layout(10L, "Shared");
        jira.scheme(20L, "PAY scheme", null);
        when(jira.manager.getFieldLayoutSchemeEntities(any(com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme.class)))
                .thenThrow(new IllegalStateException("broken"));
        when(jira.manager.getProjectsUsing(any(com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme.class)))
                .thenThrow(new IllegalStateException("broken"));

        FieldConfigurationSearchResponse configuration = search(Kind.CONFIGURATION, "10", null, 0, 1);
        assertEquals(FieldConfigurationSearchResponse.Status.OK, configuration.getStatus());
        assertFalse(configuration.isUsageComplete());
        Item item = only(configuration);
        assertEquals(Long.valueOf(10L), item.getFieldConfiguration().getId());
        assertEquals(UsageStatus.UNAVAILABLE, item.getUsage().getStatus());
        assertNull("no list that could be read as empty", item.getUsage().getLinks());
        assertNull(item.getUsage().getTotal());
        assertFalse(item.getUsage().isTruncated());
        assertTrue(item.getUsage().getMessage().contains("not mean the object is unused"));

        FieldConfigurationSearchResponse scheme = search(Kind.SCHEME, "20", null, 0, 1);
        assertFalse(scheme.isUsageComplete());
        assertEquals(UsageStatus.UNAVAILABLE, only(scheme).getUsage().getStatus());
        assertNull(only(scheme).getUsage().getProjects());
    }

    @Test
    public void searchOnlyReadsAndRefusesANonAdministratorBeforeTouchingJira() {
        jira.layout(10L, "Shared");
        jira.scheme(20L, "PAY scheme", null);
        search(Kind.CONFIGURATION, null, "s", 0, 20);
        search(Kind.SCHEME, null, "p", 0, 20);
        search(Kind.SCHEME, "DEFAULT", null, 0, 1);
        for (Invocation call : mockingDetails(jira.manager).getInvocations()) {
            String method = call.getMethod().getName();
            assertTrue("read-only calls only, saw " + method,
                    method.startsWith("get") || method.equals("refresh"));
        }

        AdministratorGuard denied = mock(AdministratorGuard.class);
        doThrow(new SecurityException("not an administrator")).when(denied).requireAdministrator();
        com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager untouched =
                mock(com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager.class);
        FieldConfigurationSearcher guarded = new FieldConfigurationSearcher(denied, jira.catalog, untouched, projectManager);
        try {
            guarded.search(Kind.CONFIGURATION, "10", null, 0, 1, 0, 20);
            org.junit.Assert.fail("a non-administrator must be refused");
        } catch (SecurityException expected) {
            verifyNoInteractions(untouched);
        }
    }

    private FieldConfigurationSearchResponse search(Kind kind, String id, String name, int offset, int limit) {
        return searcher.search(kind, id, name, offset, limit, 0, 20);
    }

    private static final class LinkViewAccess {
        final ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse.LinkView link;

        LinkViewAccess(ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse.LinkView link) { this.link = link; }

        String scheme() { return link.getScheme().getName(); }
    }

    private static Item only(FieldConfigurationSearchResponse response) {
        assertEquals(response.getMessage(), FieldConfigurationSearchResponse.Status.OK, response.getStatus());
        assertEquals(1, response.getItems().size());
        return response.getItems().get(0);
    }
}
