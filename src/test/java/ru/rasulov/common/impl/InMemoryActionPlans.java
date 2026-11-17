package ru.rasulov.common.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Stateful ActionPlanStore mock for service tests. */
public final class InMemoryActionPlans {
    private InMemoryActionPlans() {}

    public static ActionPlanStore create() {
        ActionPlanStore store = mock(ActionPlanStore.class);
        Map<String, ActionPlanStore.Plan> plans = new HashMap<String, ActionPlanStore.Plan>();
        when(store.issue(anyString(), nullable(String.class), anyString(), anyString())).thenAnswer(call -> {
            String id = UUID.randomUUID().toString();
            ActionPlanStore.Plan plan = new ActionPlanStore.Plan(id, call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), Long.MAX_VALUE);
            plans.put(id, plan);
            return plan;
        });
        when(store.find(anyString(), nullable(String.class), anyString())).thenAnswer(call -> plans.get(call.getArgument(2)));
        when(store.peekRequest(anyString(), nullable(String.class), anyString())).thenAnswer(call -> {
            ActionPlanStore.Plan plan = plans.get(call.getArgument(2));
            return plan == null ? null : plan.getRequest();
        });
        doAnswer(call -> { plans.remove((String) call.getArgument(0)); return null; })
                .when(store).remove(anyString());
        return store;
    }
}
