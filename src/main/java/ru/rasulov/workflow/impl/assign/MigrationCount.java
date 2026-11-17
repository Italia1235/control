package ru.rasulov.workflow.impl.assign;

/**
 * Per-call counts only; exact affected IDs and original states live exclusively in the durable journal. {@code get()}
 * is the issues whose move was confirmed by reading them back; {@code attempted()} also counts a move whose native call
 * started but was not confirmed, so a call that failed in the middle of a move is distinguishable from one that failed
 * while only planning.
 */
final class MigrationCount {
    private int count;
    private int attempts;
    void attempting() { attempts++; }
    void increment() { count++; }
    int get() { return count; }
    int attempted() { return attempts; }
}
