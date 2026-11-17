package ru.rasulov.common.impl.undo;

/** Durable undo capacity was exhausted before a mutation. */
public final class JournalCapacityException extends RuntimeException {
    public JournalCapacityException(String message) { super(message); }
}
