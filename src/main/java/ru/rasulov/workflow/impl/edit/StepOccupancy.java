package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.ofbiz.core.entity.EntityCondition;
import org.ofbiz.core.entity.EntityConditionList;
import org.ofbiz.core.entity.EntityExpr;
import org.ofbiz.core.entity.EntityFindOptions;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericEntityException;
import org.ofbiz.core.entity.GenericValue;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Native current-step occupancy across every project using a workflow, without the issue index. */
@Named
public class StepOccupancy {
    private static final int PAGE_SIZE = 500;
    private final OfBizDelegator delegator;

    @Inject
    public StepOccupancy(@ComponentImport OfBizDelegator delegator) {
        this.delegator = delegator;
    }

    public Map<Integer, Long> occupied(String workflowName, Set<Integer> stepIds) {
        Map<Integer, Long> counts = new TreeMap<Integer, Long>();
        for (Integer stepId : stepIds) counts.put(stepId, 0L);
        if (counts.isEmpty()) return counts;
        // Jira's entity model has no view joining entry.name to currentStep.stepId. Page entry ids,
        // close each cursor before querying current steps, and keep every IN predicate bounded.
        long after = 0;
        while (true) {
            List<Long> entries = entries(workflowName, after);
            if (entries.isEmpty()) return counts;
            for (Map.Entry<Integer, Long> count : counts.entrySet()) {
                EntityCondition condition = new EntityConditionList(Arrays.<EntityCondition>asList(
                        new EntityExpr("entryId", EntityOperator.IN, entries),
                        new EntityExpr("stepId", EntityOperator.EQUALS, count.getKey())), EntityOperator.AND);
                try {
                    long batch = delegator.getDelegatorInterface().countByCondition("OSCurrentStep", "id",
                            condition, null);
                    count.setValue(count.getValue() + batch);
                } catch (GenericEntityException failure) {
                    throw new IllegalStateException("Could not count current steps for workflow " + workflowName,
                            failure);
                }
            }
            if (entries.size() < PAGE_SIZE) return counts;
            after = entries.get(entries.size() - 1);
        }
    }

    private List<Long> entries(String name, long after) {
        EntityCondition condition = new EntityConditionList(Arrays.<EntityCondition>asList(
                new EntityExpr("name", EntityOperator.EQUALS, name),
                new EntityExpr("id", EntityOperator.GREATER_THAN, after)), EntityOperator.AND);
        EntityFindOptions options = new EntityFindOptions().maxResults(PAGE_SIZE).fetchSize(PAGE_SIZE);
        List<Long> ids = new ArrayList<Long>(PAGE_SIZE);
        try (OfBizListIterator rows = delegator.findListIteratorByCondition("OSWorkflowEntry", condition, null,
                Collections.singletonList("id"), Collections.singletonList("id ASC"), options)) {
            GenericValue row;
            while (ids.size() < PAGE_SIZE && (row = rows.next()) != null) ids.add(row.getLong("id"));
        }
        return ids;
    }
}
