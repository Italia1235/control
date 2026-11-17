package ru.rasulov.common.impl.undo;

import net.java.ao.Entity;
import net.java.ao.schema.Unique;

public interface JournalBudget extends Entity {
    @Unique
    String getBudgetKey();
    void setBudgetKey(String value);

    long getUsedBytes();
    void setUsedBytes(long value);
}
