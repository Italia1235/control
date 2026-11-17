package ru.rasulov.common.impl;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

public class PreviewPlanExecutorTest {
    public static class Req {
        public boolean dryRun;
        public String planId;
        public String name;
    }

    private final ActionPlanStore plans = mock(ActionPlanStore.class);
    private final ActionPlanCodec codec = new ActionPlanCodec();
    private final Map<String, ActionPlanStore.Plan> saved = new HashMap<>();
    private final PreviewPlanExecutor executor = new PreviewPlanExecutor(plans, codec);

    {
        when(plans.issue(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            ActionPlanStore.Plan plan = new ActionPlanStore.Plan("p1", call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), 99L);
            saved.put("p1", plan);
            return plan;
        });
        when(plans.find(anyString(), anyString(), anyString())).thenAnswer(call -> {
            ActionPlanStore.Plan plan = saved.get(call.getArgument(2));
            return plan != null && plan.getRequest() != null && "op".equals(call.getArgument(0))
                    && "alice".equals(call.getArgument(1)) ? plan : null;
        });
        doAnswer(call -> { saved.remove(call.getArgument(0)); return null; }).when(plans).remove(anyString());
    }

    private String issue(String fingerprint) {
        Req request = new Req();
        request.name = "x";
        return executor.issue("op", "alice", request, fingerprint, (id, expires) -> id + "@" + expires);
    }

    private String apply(String actor, String operation, boolean writable, String fingerprint) {
        return executor.apply(operation, actor, "p1", Req.class, () -> "stale",
                request -> request, request -> writable, request -> fingerprint, request -> "written:" + request.name);
    }

    @Test public void appliesMatchingPlanOnceAndConsumesIt() {
        assertEquals("p1@99", issue("fp"));
        assertEquals("written:x", apply("alice", "op", true, "fp"));
        assertTrue(saved.isEmpty());
        assertEquals("stale", apply("alice", "op", true, "fp"));
    }

    @Test public void foreignActorOrOperationIsStaleAndKeepsPlan() {
        issue("fp");
        assertEquals("stale", apply("bob", "op", true, "fp"));
        assertEquals("stale", apply("alice", "other", true, "fp"));
        assertTrue(saved.containsKey("p1"));
    }

    @Test public void changedFingerprintIsStaleAndConsumesPlan() {
        issue("fp");
        assertEquals("stale", apply("alice", "op", true, "changed"));
        assertTrue(saved.isEmpty());
    }

    @Test public void notWritableIsStaleAndConsumesPlan() {
        issue("fp");
        assertEquals("stale", apply("alice", "op", false, "fp"));
        assertTrue(saved.isEmpty());
    }

    @Test public void prepareFailureConsumesPlanAndPropagates() {
        issue("fp");
        try {
            executor.apply("op", "alice", "p1", Req.class, () -> "stale",
                    request -> { throw new IllegalStateException("prepare"); }, p -> true, p -> "fp", p -> "w");
            fail();
        } catch (IllegalStateException e) {
            assertEquals("prepare", e.getMessage());
        }
        assertTrue(saved.isEmpty());
    }

    @Test public void writeFailureConsumesPlanAndPropagates() {
        issue("fp");
        try {
            executor.apply("op", "alice", "p1", Req.class, () -> "stale",
                    request -> request, p -> true, p -> "fp", p -> { throw new IllegalStateException("write"); });
            fail();
        } catch (IllegalStateException e) {
            assertEquals("write", e.getMessage());
        }
        assertNull(saved.get("p1"));
    }
}
