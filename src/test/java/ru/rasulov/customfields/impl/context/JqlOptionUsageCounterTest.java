package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.bc.issue.search.SearchService.ParseResult;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.MessageSet;
import com.atlassian.query.Query;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Usage is counted by the id of the option with the administrator's own search permissions, within a time budget. */
public class JqlOptionUsageCounterTest {
    private final SearchService search = mock(SearchService.class);
    private final ApplicationUser admin = mock(ApplicationUser.class);
    private final CustomField field = mock(CustomField.class);
    private final AtomicLong nanos = new AtomicLong();
    private final JqlOptionUsageCounter counter = new JqlOptionUsageCounter(search, nanos::get);

    private JqlOptionUsageCounterTest withValidJql(String jql, Query query) {
        MessageSet clean = mock(MessageSet.class);
        when(clean.hasAnyErrors()).thenReturn(false);
        when(search.parseQuery(admin, jql)).thenReturn(new ParseResult(query, clean));
        return this;
    }

    public JqlOptionUsageCounterTest() { when(field.getId()).thenReturn("customfield_10100"); }

    private static Map<Long, Long> ask(Object... pairs) {
        Map<Long, Long> options = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) options.put((Long) pairs[i], (Long) pairs[i + 1]);
        return options;
    }

    @Test public void aTopLevelOptionIsCountedByItsIdAndAChildByItsParentAndChildIds() throws Exception {
        Query root = mock(Query.class), child = mock(Query.class);
        withValidJql("cf[10100] = 501", root).withValidJql("cf[10100] in cascadeOption(10, 11)", child);
        when(search.searchCount(admin, root)).thenReturn(7L);
        when(search.searchCount(admin, child)).thenReturn(3L);

        Map<Long, OptionUsage> usage = counter.count(admin, field, ask(501L, null, 11L, 10L));

        assertEquals(Integer.valueOf(7), usage.get(501L).getIssues());
        assertEquals(Integer.valueOf(3), usage.get(11L).getIssues());
        assertTrue(usage.get(501L).isKnown());
        verify(search).parseQuery(admin, "cf[10100] = 501");
        verify(search).parseQuery(admin, "cf[10100] in cascadeOption(10, 11)");
        verify(search, never()).searchCountOverrideSecurity(any(), any());
    }

    @Test public void anInvalidQueryASearchFailureOrAnyOtherErrorIsUnknownWithAReasonAndDoesNotStopTheRest() throws Exception {
        MessageSet bad = mock(MessageSet.class);
        when(bad.hasAnyErrors()).thenReturn(true);
        when(bad.getErrorMessages()).thenReturn(Collections.singleton("Field 'cf[10100]' does not exist"));
        when(search.parseQuery(admin, "cf[10100] = 1")).thenReturn(new ParseResult(null, bad));
        Query failing = mock(Query.class), broken = mock(Query.class), fine = mock(Query.class);
        withValidJql("cf[10100] = 2", failing).withValidJql("cf[10100] = 3", broken).withValidJql("cf[10100] = 4", fine);
        when(search.searchCount(admin, failing)).thenThrow(new SearchException("index unavailable"));
        when(search.searchCount(admin, broken)).thenThrow(new IllegalStateException("boom"));
        when(search.searchCount(admin, fine)).thenReturn(0L);

        Map<Long, OptionUsage> usage = counter.count(admin, field, ask(1L, null, 2L, null, 3L, null, 4L, null));

        assertFalse(usage.get(1L).isKnown());
        assertTrue(usage.get(1L).getReason(), usage.get(1L).getReason().contains("does not exist"));
        assertFalse(usage.get(2L).isKnown());
        assertTrue(usage.get(2L).getReason(), usage.get(2L).getReason().contains("index unavailable"));
        assertFalse(usage.get(3L).isKnown());
        assertTrue(usage.get(3L).getReason(), usage.get(3L).getReason().contains("boom"));
        assertEquals(Integer.valueOf(0), usage.get(4L).getIssues());
    }

    @Test public void theTimeBudgetIsTenSecondsForAllSearchesTogether() throws Exception {
        assertEquals(10_000L, JqlOptionUsageCounter.BUDGET_MILLIS);
        Query slow = mock(Query.class), unreached = mock(Query.class);
        withValidJql("cf[10100] = 1", slow).withValidJql("cf[10100] = 2", unreached);
        when(search.searchCount(admin, slow)).thenAnswer(call -> {
            nanos.addAndGet((JqlOptionUsageCounter.BUDGET_MILLIS + 1) * 1_000_000L);
            return 5L;
        });

        Map<Long, OptionUsage> usage = counter.count(admin, field, ask(1L, null, 2L, null));

        assertFalse("a search that ends after the budget is not trusted", usage.get(1L).isKnown());
        assertTrue(usage.get(1L).getReason(), usage.get(1L).getReason().toLowerCase().contains("time budget"));
        assertFalse(usage.get(2L).isKnown());
        assertTrue(usage.get(2L).getReason().toLowerCase().contains("time budget"));
        verify(search, never()).searchCount(admin, unreached);
        verify(search, times(1)).searchCount(eq(admin), any(Query.class));
    }

    @Test public void aSearchWithinTheBudgetIsTrustedEvenWhenItUsesMostOfIt() throws Exception {
        Query query = mock(Query.class);
        withValidJql("cf[10100] = 1", query);
        when(search.searchCount(admin, query)).thenAnswer(call -> {
            nanos.addAndGet((JqlOptionUsageCounter.BUDGET_MILLIS - 1) * 1_000_000L);
            return 9L;
        });

        assertEquals(Integer.valueOf(9), counter.count(admin, field, ask(1L, null)).get(1L).getIssues());
    }

    @Test public void aFieldWithoutACustomFieldIdIsUnknownWithoutSearching() throws Exception {
        when(field.getId()).thenReturn("summary");

        Map<Long, OptionUsage> usage = counter.count(admin, field, ask(1L, null));

        assertFalse(usage.get(1L).isKnown());
        assertTrue(usage.get(1L).getReason(), usage.get(1L).getReason().contains("summary"));
        verifyNoInteractions(search);
    }
}
