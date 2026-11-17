package ru.rasulov.screens.impl;

import ru.rasulov.common.impl.undo.ChangeJournal;
import java.util.function.Supplier;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Planner/writer fixtures leave durable journal behavior to the journal integration suite. */
final class ScreenTestSupport {
    private ScreenTestSupport() { }
    @SuppressWarnings("unchecked")
    static ChangeJournal operations() {
        ChangeJournal journal = mock(ChangeJournal.class);
        when(journal.execute(anyString(), anyString(), any())).thenAnswer(call ->
                ((Supplier<Object>) call.getArgument(2)).get());
        return journal;
    }
}
