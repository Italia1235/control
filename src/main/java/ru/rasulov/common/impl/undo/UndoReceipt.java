package ru.rasulov.common.impl.undo;

/** Evidence captured after a selected native issue undo; never inferred from matching logical fields. */
public final class UndoReceipt {
    private final long updated;
    private final String stateHash;
    private final long stepId;

    public UndoReceipt(long updated, String stateHash, long stepId) {
        if (stateHash == null || stateHash.length() != 64) throw new IllegalArgumentException("Invalid issue state proof");
        for (int index = 0; index < stateHash.length(); index++) {
            char value = stateHash.charAt(index);
            if ((value < '0' || value > '9') && (value < 'a' || value > 'f')) {
                throw new IllegalArgumentException("Invalid issue state proof");
            }
        }
        this.updated = updated;
        this.stateHash = stateHash;
        this.stepId = stepId;
    }

    public long getUpdated() { return updated; }
    public String getStateHash() { return stateHash; }
    long getStepId() { return stepId; }
}
