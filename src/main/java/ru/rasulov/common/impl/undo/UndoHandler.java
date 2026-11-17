package ru.rasulov.common.impl.undo;

import com.atlassian.jira.user.ApplicationUser;

import java.util.List;
import java.util.Objects;
import java.util.Map;

/** Feature-owned compensation. Implementations must not inject ChangeJournal. */
public interface UndoHandler {
    String kind();
    List<String> lockNames(UndoStep step);
    String conflict(UndoStep step);
    void undo(UndoStep step, ApplicationUser actor);

    /** Full selection allows proving that references are removed by selected dependent steps. */
    default String conflict(UndoStep step, List<UndoStep> selection) { return conflict(step); }

    /** A handler may normalize auxiliary metadata, but must compare every mutable domain value. */
    default boolean sameState(String leftJson, String rightJson) { return Objects.equals(leftJson, rightJson); }

    /** Compare adjacent selected writes, including proven cross-resource effects between them. */
    default boolean sameState(UndoStep older, UndoStep newer, List<UndoStep> selection) {
        return sameState(older.getAfterJson(), newer.getBeforeJson());
    }

    /** Recheck dependent state at the write boundary, not just at preview time. */
    default void undo(UndoStep step, ApplicationUser actor, List<UndoStep> selection) { undo(step, actor); }

    /** Exact native issue proofs committed by earlier selected undo steps, never guessed from logical state. */
    default String conflict(UndoStep step, List<UndoStep> selection, Map<Long, UndoReceipt> restoredUpdated) {
        return conflict(step, selection);
    }

    default void undo(UndoStep step, ApplicationUser actor, List<UndoStep> selection, Map<Long, UndoReceipt> restoredUpdated) {
        undo(step, actor, selection);
    }

    /** Optional compact {"issues":[[id,updated,stateHash],...]} receipt after a successful native undo. */
    default String undoReceipt(UndoStep step) { return null; }

    /**
     * Extra structured identity of an object that this step's confirmed post-state names, merged into the public
     * step of {@code get_operation}. Empty when the step has no proven post-state: an unknown identity is never invented.
     */
    default Map<String, Object> publicIdentity(UndoStep step) { return java.util.Collections.emptyMap(); }
}
