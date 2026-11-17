package ru.rasulov.common.impl;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Single-use preview protocol shared by mutating services: issue a plan, then on apply find it, re-prepare the saved
 * preview request, compare fingerprints and always consume the plan. Journal, lock, domain planning, fingerprint
 * computation and writes stay with the caller; {@link #apply} must be called from inside the caller's journal/lock.
 */
@Named
public class PreviewPlanExecutor {
    private final ActionPlanStore plans;
    private final ActionPlanCodec codec;

    @Inject
    public PreviewPlanExecutor(ActionPlanStore plans, ActionPlanCodec codec) {
        this.plans = plans;
        this.codec = codec;
    }

    /** Stores the preview {@code request} with its {@code fingerprint} and hands id and expiry to {@code withPlan}. */
    public <R> R issue(String operation, String actorKey, Object request, String fingerprint,
            BiFunction<String, Long, R> withPlan) {
        ActionPlanStore.Plan plan = plans.issue(operation, actorKey, codec.request(request), fingerprint);
        return withPlan.apply(plan.getId(), plan.getExpiresAt());
    }

    /**
     * Returns {@code stale} when the plan is missing, when the re-prepared preview is not {@code writable}, or when its
     * fingerprint differs from the saved one; otherwise the result of {@code write}. The plan is removed in every case,
     * including when {@code prepare}, {@code fingerprint} or {@code write} throw.
     */
    public <Q, P, R> R apply(String operation, String actorKey, String planId, Class<Q> requestType,
            Supplier<R> stale, Function<Q, P> prepare, Predicate<P> writable,
            Function<P, String> fingerprint, Function<P, R> write) {
        ActionPlanStore.Plan saved = plans.find(operation, actorKey, planId);
        if (saved == null) return stale.get();
        try {
            P current = prepare.apply(codec.mode(saved.getRequest(), true, requestType));
            if (!writable.test(current) || !saved.getFingerprint().equals(fingerprint.apply(current))) return stale.get();
            return write.apply(current);
        } finally {
            plans.remove(planId);
        }
    }
}
