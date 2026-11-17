package ru.rasulov.common.impl.applies;

import net.java.ao.Query;

import java.util.Map;
import java.util.function.Consumer;

/** Storage surface of the apply journal: inserts in their own transaction and reads. Rows are never updated or deleted. */
interface ApplyDatabase {
    /** Inserts one row in its own transaction; fails if the unique {@code EVENT_KEY} exists. */
    void insert(Map<String, Object> columns);

    boolean exists(String eventKey);

    ApplyRecord[] find(Query query);

    int count(Query query);

    /** Reads the selected columns of every matching row one by one, without holding the table in memory. */
    void stream(Query query, Consumer<ApplyRecord> each);
}
