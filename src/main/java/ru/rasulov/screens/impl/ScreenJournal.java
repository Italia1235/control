package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoStep;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.Map;
import java.util.function.Supplier;

/** Intent is committed before every standalone screen root or child mutation. */
@Named
public class ScreenJournal {
    private final ChangeJournal journal;
    private final ScreenUndoHandler states;
    @Inject public ScreenJournal(ChangeJournal journal, ScreenUndoHandler states) {
        this.journal = journal; this.states = states;
    }
    static long budget(long snapshotUpperBound, long writes) {
        if (snapshotUpperBound > 32000L) {
            throw new ru.rasulov.common.impl.undo.JournalCapacityException(
                    "This screen object's undo snapshot exceeds the bounded journal step limit.");
        }
        return 71680L * writes;
    }
    void reserve(long bytes) { journal.reserve(bytes); }
    private void write(String resource, Map<String, Object> before, Runnable work,
                       Supplier<Map<String, Object>> after) {
        // Manager child writes invalidate their cache, not a retained parent's lazy child collection.
        // Capture persisted state on both sides so every step describes the same native state chain.
        if (Boolean.TRUE.equals(before.get("exists"))) before = states.persisted(before);
        UndoStep step = journal.prepare("screen.change", resource, ScreenUndoHandler.json(before), null);
        try {
            work.run();
        } catch (RuntimeException failure) {
            // Only a fresh persisted BEFORE proves a failed native call changed nothing.
            // Unknown creates and partial child writes retain their PREPARED ambiguity.
            if (Boolean.TRUE.equals(before.get("exists"))) {
                try {
                    if (ScreenUndoHandler.json(before).equals(ScreenUndoHandler.json(states.persisted(before)))) {
                        journal.discard(step);
                    }
                } catch (RuntimeException reconciliation) { failure.addSuppressed(reconciliation); }
            }
            throw failure;
        }
        journal.applied(step, ScreenUndoHandler.json(states.persisted(after.get())));
        journal.checkpoint();
    }
    void create(FieldScreen screen, Runnable work) {
        write("screen:" + screen.getName(), ScreenUndoHandler.absent("screen", screen.getName()), work,
                () -> states.snapshot(screen));
    }
    void change(FieldScreen screen, Runnable work) {
        write("screen:" + screen.getName(), states.snapshot(screen), work, () -> states.snapshot(screen));
    }
}
