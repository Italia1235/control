package ru.rasulov.common.impl;

import com.atlassian.cache.Cache;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ActionPlanStoreTest {
    @SuppressWarnings("unchecked")
    @Test
    public void bindsPlansToOperationAndActor() {
        Cache<String, ActionPlanStore.Plan> cache = mock(Cache.class);
        AtomicLong now = new AtomicLong(100L);
        ActionPlanStore store = new ActionPlanStore(cache, now::get);
        final ActionPlanStore.Plan[] saved = new ActionPlanStore.Plan[1];
        doAnswer(invocation -> {
            saved[0] = invocation.getArgument(1);
            return null;
        }).when(cache).put(anyString(), any(ActionPlanStore.Plan.class));
        when(cache.get(anyString())).thenAnswer(invocation -> saved[0]);

        ActionPlanStore.Plan plan = store.issue("create", "admin", "request", "fingerprint");

        assertSame(plan, store.find("create", "admin", plan.getId()));
        assertNull(store.find("other", "admin", plan.getId()));
        assertNull(store.find("create", "other", plan.getId()));
        verify(cache, never()).remove(plan.getId());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void expiresAndRemovesPlansAtTheDeadline() {
        Cache<String, ActionPlanStore.Plan> cache = mock(Cache.class);
        AtomicLong now = new AtomicLong(100L);
        ActionPlanStore store = new ActionPlanStore(cache, now::get);
        final ActionPlanStore.Plan[] saved = new ActionPlanStore.Plan[1];
        doAnswer(invocation -> {
            saved[0] = invocation.getArgument(1);
            return null;
        }).when(cache).put(anyString(), any(ActionPlanStore.Plan.class));
        when(cache.get(anyString())).thenAnswer(invocation -> saved[0]);

        ActionPlanStore.Plan plan = store.issue("create", "admin", "request", "fingerprint");
        now.set(plan.getExpiresAt());

        assertNull(store.find("create", "admin", plan.getId()));
        verify(cache).remove(plan.getId());
    }

    @SuppressWarnings("unchecked")
    private ActionPlanStore store(AtomicLong now) {
        return new ActionPlanStore(mock(Cache.class), now::get);
    }

    @Test
    public void aPlanOverTheByteLimitIsRefusedAndNotStored() {
        Cache<String, ActionPlanStore.Plan> cache = mock(Cache.class);
        ActionPlanStore store = new ActionPlanStore(cache, new AtomicLong(1)::get);
        String huge = new String(new char[ActionPlanStore.MAX_PLAN_CHARS]).replace('\0', 'x');

        try {
            store.issue("create", "admin", huge, "fingerprint");
            fail("an oversize plan must be refused");
        } catch (ru.rasulov.common.api.exception.InvalidRequestException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("too large"));
        }
        verify(cache, never()).put(anyString(), any(ActionPlanStore.Plan.class));
    }

    @Test
    public void anActorCannotHoldMoreOpenPlansThanTheQuotaButOthersAreUnaffected() {
        ActionPlanStore store = store(new AtomicLong(1));
        for (int i = 0; i < ActionPlanStore.MAX_PLANS_PER_ACTOR; i++) {
            store.issue("create", "admin", "request", "fingerprint");
        }

        try {
            store.issue("create", "admin", "request", "fingerprint");
            fail("the plan over the quota must be refused");
        } catch (ru.rasulov.common.api.exception.BusyException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("open previews"));
        }
        assertNotNull(store.issue("create", "other", "request", "fingerprint"));
    }

    @Test
    public void applyingOrExpiryReleasesTheQuota() {
        AtomicLong now = new AtomicLong(1);
        ActionPlanStore store = store(now);
        ActionPlanStore.Plan first = store.issue("create", "admin", "request", "fingerprint");
        for (int i = 1; i < ActionPlanStore.MAX_PLANS_PER_ACTOR; i++) {
            store.issue("create", "admin", "request", "fingerprint");
        }

        store.remove(first.getId());
        ActionPlanStore.Plan again = store.issue("create", "admin", "request", "fingerprint");

        now.set(again.getExpiresAt());
        for (int i = 0; i < ActionPlanStore.MAX_PLANS_PER_ACTOR; i++) {
            store.issue("create", "admin", "request", "fingerprint");
        }
    }

    @Test
    public void statusRecognizesOpenPlansWithoutRequiringRequestText() {
        Fixture f = new Fixture();
        ActionPlanStore.Plan plan = f.store.issue("create", "admin", null, "fingerprint");

        assertEquals(ActionPlanStore.Status.OPEN, f.store.status("create", "admin", plan.getId()));
        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("create", "admin", null));
        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("create", "admin", "unknown"));
        assertSame(plan, f.store.find("create", "admin", plan.getId()));
        assertNull(f.store.peekRequest("create", "admin", plan.getId()));
        assertTrue(f.markers.containsKey(plan.getId()));
    }

    @Test
    public void anEvictedPayloadIsUnknownBeforeItsDeadlineButExpiredAtTheDeadline() {
        Fixture f = new Fixture();
        ActionPlanStore.Plan plan = f.store.issue("create", "admin", "request", "fingerprint");
        f.payloads.clear();
        f.now.set(plan.getExpiresAt() - 1);

        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("create", "admin", plan.getId()));
        assertNull(f.store.find("create", "admin", plan.getId()));
        assertNull(f.store.peekRequest("create", "admin", plan.getId()));

        f.now.set(plan.getExpiresAt());
        assertEquals(ActionPlanStore.Status.EXPIRED, f.store.status("create", "admin", plan.getId()));
        assertNull(f.store.find("create", "admin", plan.getId()));
        assertNull(f.store.peekRequest("create", "admin", plan.getId()));

        ActionPlanStore otherNode = new ActionPlanStore(f.cache, f.markerCache, f.now::get);
        assertEquals(ActionPlanStore.Status.EXPIRED, otherNode.status("create", "admin", plan.getId()));
    }

    @Test
    public void statusNeverDisclosesForeignOrWrongOperationPlans() {
        Fixture f = new Fixture();
        ActionPlanStore.Plan plan = f.store.issue("create", "admin", "request", "fingerprint");

        assertPrivateStatus(f, plan);
        f.payloads.clear();
        f.now.set(plan.getExpiresAt());
        assertPrivateStatus(f, plan);
        assertEquals(ActionPlanStore.Status.EXPIRED, f.store.status("create", "admin", plan.getId()));
        verify(f.cache, never()).remove(anyString());
        verify(f.markerCache, never()).remove(anyString());
    }

    private void assertPrivateStatus(Fixture f, ActionPlanStore.Plan plan) {
        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("create", "other", plan.getId()));
        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("other", "admin", plan.getId()));
        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status(null, "admin", plan.getId()));
        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("create", null, plan.getId()));
        assertNull(f.store.find("create", "other", plan.getId()));
        assertNull(f.store.find("other", "admin", plan.getId()));
        assertNull(f.store.peekRequest("create", "other", plan.getId()));
        assertNull(f.store.peekRequest("other", "admin", plan.getId()));
    }

    @Test
    public void findExpiryRemovesPayloadButPreservesTheMarkerUntilExplicitRemoval() {
        Fixture f = new Fixture();
        ActionPlanStore.Plan plan = f.store.issue("create", "admin", "request", "fingerprint");
        f.now.set(plan.getExpiresAt());

        assertNull(f.store.find("create", "admin", plan.getId()));
        assertFalse(f.payloads.containsKey(plan.getId()));
        assertTrue(f.markers.containsKey(plan.getId()));
        assertEquals(ActionPlanStore.Status.EXPIRED, f.store.status("create", "admin", plan.getId()));

        f.store.remove(plan.getId());
        assertFalse(f.markers.containsKey(plan.getId()));
        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("create", "admin", plan.getId()));
    }

    @Test
    public void markerRetentionEndsTenMinutesAfterTheOriginalDeadlineWithoutRefresh() {
        Fixture f = new Fixture();
        ActionPlanStore.Plan plan = f.store.issue("create", "admin", "request", "fingerprint");
        f.now.set(plan.getExpiresAt());
        assertNull(f.store.find("create", "admin", plan.getId()));
        assertEquals(ActionPlanStore.Status.EXPIRED, f.store.status("create", "admin", plan.getId()));
        f.now.set(plan.getExpiresAt() + ActionPlanStore.TTL_MILLIS - 1);
        assertEquals(ActionPlanStore.Status.EXPIRED, f.store.status("create", "admin", plan.getId()));

        f.now.incrementAndGet();
        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("create", "admin", plan.getId()));
        verify(f.markerCache).put(anyString(), any(ActionPlanStore.Marker.class));
    }

    @Test
    public void consumptionClearsMetadataAndCannotLaterLookExpired() {
        Fixture f = new Fixture();
        ActionPlanStore.Plan plan = f.store.issue("create", "admin", "request", "fingerprint");

        f.store.remove(plan.getId());
        assertTrue(f.payloads.isEmpty());
        assertTrue(f.markers.isEmpty());
        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("create", "admin", plan.getId()));
        f.now.set(plan.getExpiresAt());
        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("create", "admin", plan.getId()));
    }

    @Test
    public void evictionOfBothPayloadAndMarkerLeavesNoExpiryInformation() {
        Fixture f = new Fixture();
        ActionPlanStore.Plan plan = f.store.issue("create", "admin", "request", "fingerprint");
        f.payloads.clear();
        f.markers.clear();
        f.now.set(plan.getExpiresAt());

        assertEquals(ActionPlanStore.Status.NOT_FOUND, f.store.status("create", "admin", plan.getId()));
    }

    private static final class Fixture {
        private final AtomicLong now = new AtomicLong(100L);
        private final Map<String, ActionPlanStore.Plan> payloads = new HashMap<>();
        private final Map<String, ActionPlanStore.Marker> markers = new HashMap<>();
        private final Cache<String, ActionPlanStore.Plan> cache = backingCache(payloads);
        private final Cache<String, ActionPlanStore.Marker> markerCache = backingCache(markers);
        private final ActionPlanStore store = new ActionPlanStore(cache, markerCache, now::get);
    }

    @SuppressWarnings("unchecked")
    private static <T> Cache<String, T> backingCache(Map<String, T> values) {
        Cache<String, T> cache = mock(Cache.class);
        when(cache.get(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(cache).put(anyString(), any());
        doAnswer(call -> {
            values.remove(call.getArgument(0));
            return null;
        }).when(cache).remove(anyString());
        return cache;
    }
}
