package ru.rasulov.common.impl.undo;

/** The operation was cooperatively cancelled. */
public final class JournalCancelledException extends RuntimeException {
    public JournalCancelledException(String message) { super(message); }
}
