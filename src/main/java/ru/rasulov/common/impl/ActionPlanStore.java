package ru.rasulov.common.impl;

import com.atlassian.cache.Cache;
import com.atlassian.cache.CacheManager;
import com.atlassian.cache.CacheSettingsBuilder;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Cluster-replicated, short-lived previews used by every mutating operation. */
@Named
public class ActionPlanStore {
    public static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    /** Expiry metadata survives for ten minutes after the preview payload expires. */
    private static final long MARKER_TTL_MILLIS = TTL_MILLIS * 2;
    public enum Status { OPEN, EXPIRED, NOT_FOUND }
    /** Characters of request and fingerprint of one stored preview together. */
    public static final int MAX_PLAN_CHARS = 1_000_000;
    /** Open previews one actor may hold on this node. */
    public static final int MAX_PLANS_PER_ACTOR = 100;
    private final Cache<String, Plan> cache;
    private final Cache<String, Marker> markers;
    private final LongSupplier clock;
    private final Map<String, Map<String, Long>> openByActor = new HashMap<String, Map<String, Long>>();
    private final Map<String, String> actorOfPlan = new HashMap<String, String>();

    @Inject
    public ActionPlanStore(@ComponentImport CacheManager manager) {
        this(manager.getCache("ru.rasulov.control.action-plans", null, new CacheSettingsBuilder()
                .expireAfterWrite(TTL_MILLIS, TimeUnit.MILLISECONDS).maxEntries(10000)
                .replicateSynchronously().replicateViaCopy().build()),
                manager.getCache("ru.rasulov.control.action-plan-markers", null, new CacheSettingsBuilder()
                        .expireAfterWrite(MARKER_TTL_MILLIS, TimeUnit.MILLISECONDS).maxEntries(10000)
                        .replicateSynchronously().replicateViaCopy().build()),
                System::currentTimeMillis);
    }

    ActionPlanStore(Cache<String, Plan> cache, LongSupplier clock) {
        this(cache, null, clock);
    }

    ActionPlanStore(Cache<String, Plan> cache, Cache<String, Marker> markers, LongSupplier clock) {
        this.cache = cache;
        this.markers = markers;
        this.clock = clock;
    }

    public Plan issue(String operation, String actor, String request, String fingerprint) {
        int chars = (request == null ? 0 : request.length()) + (fingerprint == null ? 0 : fingerprint.length());
        if (chars > MAX_PLAN_CHARS) {
            throw new InvalidRequestException("The preview is too large to keep (" + chars + " characters; the limit "
                    + "is " + MAX_PLAN_CHARS + "). Nothing was stored: split the request into smaller ones.");
        }
        long expiresAt = clock.getAsLong() + TTL_MILLIS;
        Plan plan = new Plan(UUID.randomUUID().toString(), operation, actor, request, fingerprint, expiresAt);
        reserve(actor, plan.id, expiresAt);
        cache.put(plan.id, plan);
        if (markers != null) markers.put(plan.id, new Marker(actor, operation, expiresAt));
        return plan;
    }

    public Plan find(String operation, String actor, String id) {
        Plan plan = id == null ? null : cache.get(id);
        if (plan == null || !plan.operation.equals(operation) || !plan.actor.equals(actor)) return null;
        if (plan.expiresAt <= clock.getAsLong()) {
            cache.remove(id);
            release(id);
            return null;
        }
        return plan;
    }

    /**
     * Reports an open preview or a recently expired preview for its operation and actor only.
     * A payload evicted before its deadline is not open, and expiry metadata is forgotten ten
     * minutes after the original deadline. This lookup never consumes a preview.
     */
    public Status status(String operation, String actor, String id) {
        if (id == null) return Status.NOT_FOUND;
        Marker marker = markers == null ? null : markers.get(id);
        long now = clock.getAsLong();
        if (marker != null) {
            if (!marker.operation.equals(operation) || !marker.actor.equals(actor)) return Status.NOT_FOUND;
            if (now >= marker.expiresAt + TTL_MILLIS) return Status.NOT_FOUND;
            if (now >= marker.expiresAt) return Status.EXPIRED;
        }
        Plan plan = cache.get(id);
        return plan != null && plan.operation.equals(operation) && plan.actor.equals(actor)
                && plan.expiresAt > now ? Status.OPEN : Status.NOT_FOUND;
    }

    /**
     * The request text of an open preview of this operation and actor, without consuming it or touching its expiry
     * bookkeeping; {@code null} if the preview is unknown, of another operation, foreign or expired.
     */
    public String peekRequest(String operation, String actor, String id) {
        Plan plan = id == null ? null : cache.get(id);
        if (plan == null || !plan.operation.equals(operation) || !plan.actor.equals(actor)
                || plan.expiresAt <= clock.getAsLong()) return null;
        return plan.request;
    }

    public void remove(String id) {
        if (id == null) return;
        cache.remove(id);
        if (markers != null) markers.remove(id);
        release(id);
    }

    /**
     * Counts the previews this node issued for the actor that are still open. The count is per node, not cluster-wide:
     * a preview issued on another node is not seen here. A refusal is final for now; previews expire after
     * {@link #TTL_MILLIS} or are removed when applied.
     */
    private synchronized void reserve(String actor, String id, long expiresAt) {
        Map<String, Long> open = openByActor.get(actor);
        if (open == null) {
            open = new HashMap<String, Long>();
            openByActor.put(actor, open);
        }
        long now = clock.getAsLong();
        for (Iterator<Map.Entry<String, Long>> it = open.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() <= now) {
                actorOfPlan.remove(entry.getKey());
                it.remove();
            }
        }
        if (open.size() >= MAX_PLANS_PER_ACTOR) {
            throw new BusyException("There are already " + MAX_PLANS_PER_ACTOR + " open previews for this user. "
                    + "Apply or drop some, or wait up to " + TimeUnit.MILLISECONDS.toMinutes(TTL_MILLIS)
                    + " minutes for them to expire, then preview again.");
        }
        open.put(id, expiresAt);
        actorOfPlan.put(id, actor);
    }

    private synchronized void release(String id) {
        String actor = actorOfPlan.remove(id);
        Map<String, Long> open = actor == null ? null : openByActor.get(actor);
        if (open != null) {
            open.remove(id);
            if (open.isEmpty()) openByActor.remove(actor);
        }
    }

    /** Cluster metadata deliberately excludes request text and fingerprints. */
    static final class Marker implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String actor;
        private final String operation;
        private final long expiresAt;

        private Marker(String actor, String operation, long expiresAt) {
            this.actor = actor;
            this.operation = operation;
            this.expiresAt = expiresAt;
        }
    }

    public static final class Plan implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String id;
        private final String operation;
        private final String actor;
        private final String request;
        private final String fingerprint;
        private final long expiresAt;
        public Plan(String id, String operation, String actor, String request, String fingerprint, long expiresAt) {
            this.id = id; this.operation = operation; this.actor = actor; this.request = request;
            this.fingerprint = fingerprint; this.expiresAt = expiresAt;
        }
        public String getId() { return id; }
        public String getRequest() { return request; }
        public String getFingerprint() { return fingerprint; }
        public long getExpiresAt() { return expiresAt; }
    }
}
