package ru.rasulov.common.impl.undo;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import org.ofbiz.core.entity.EntityCondition;
import org.ofbiz.core.entity.GenericValue;

import java.util.List;
import java.util.function.Predicate;

/**
 * Looks for one matching native row without loading the table: rows come from a cursor, one at a time, and reading
 * stops at the first match. Use instead of {@code findAll}/{@code findByCondition} when the answer is only "is there
 * one".
 */
public final class NativeEntityScan {
    private NativeEntityScan() { }

    /**
     * @param condition {@code null} for every row of the entity
     * @param fields    the columns the predicate reads; keeps large unrelated columns out of the cursor
     * @return true if some row satisfies {@code match}
     */
    public static boolean anyMatch(OfBizDelegator database, String entity, EntityCondition condition,
                                   List<String> fields, Predicate<GenericValue> match) {
        try (OfBizListIterator rows = database.findListIteratorByCondition(entity, condition, null, fields, null,
                null)) {
            GenericValue row;
            while ((row = rows.next()) != null) {
                if (match.test(row)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The first row of the cursor, or {@code null} when there is none; the rest of the table is never read. */
    public static GenericValue first(OfBizDelegator database, String entity, EntityCondition condition,
                                     List<String> fields) {
        try (OfBizListIterator rows = database.findListIteratorByCondition(entity, condition, null, fields, null,
                null)) {
            return rows.next();
        }
    }
}
