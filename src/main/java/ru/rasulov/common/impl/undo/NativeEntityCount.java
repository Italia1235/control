package ru.rasulov.common.impl.undo;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import org.ofbiz.core.entity.EntityFieldMap;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericEntityException;

import java.util.Map;

/** Counts the native entity directly; Jira's getCountByAnd instead requires an entity-specific Count view. */
public final class NativeEntityCount {
    private NativeEntityCount() { }

    public static long count(OfBizDelegator database, String entity, Map<String, ?> fields) {
        try {
            return database.getDelegatorInterface().countByCondition(entity, "id",
                    new EntityFieldMap(fields, EntityOperator.AND), null);
        } catch (GenericEntityException failure) {
            throw new IllegalStateException("Could not count native entity " + entity, failure);
        }
    }
}
