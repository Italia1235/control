package ru.rasulov.common.impl.applies;

import net.java.ao.Query;

import java.util.function.Consumer;

/** Store double for the write path: reads are not used. */
abstract class FailingStore implements ApplyDatabase {
    public ApplyRecord[] find(Query query) { throw new UnsupportedOperationException(); }
    public int count(Query query) { throw new UnsupportedOperationException(); }
    public void stream(Query query, Consumer<ApplyRecord> each) { throw new UnsupportedOperationException(); }
}
