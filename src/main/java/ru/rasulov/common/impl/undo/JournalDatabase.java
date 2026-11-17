package ru.rasulov.common.impl.undo;

import net.java.ao.DBParam;
import net.java.ao.Query;
import net.java.ao.RawEntity;

import java.util.function.Supplier;

/** Small AO surface; production and real EntityManager tests use the same store. */
interface JournalDatabase {
    <T extends RawEntity<Integer>> T[] find(Class<T> type, Query query);
    <T extends RawEntity<Integer>> int count(Class<T> type, Query query);
    <T extends RawEntity<Integer>> T create(Class<T> type, DBParam... values);
    void delete(RawEntity<?>... rows);
    void flushAll();
    <T> T transaction(Supplier<T> work);
}
