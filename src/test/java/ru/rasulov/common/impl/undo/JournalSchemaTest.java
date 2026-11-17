package ru.rasulov.common.impl.undo;

import net.java.ao.RawEntity;
import net.java.ao.atlassian.AtlassianTableNameConverter;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertTrue;

public class JournalSchemaTest {
    @Test
    public void journalTablesFitJiraLimitAndHaveDistinctNames() {
        AtlassianTableNameConverter names = new AtlassianTableNameConverter(name -> "AO_3E3015_" + name);
        Set<String> tables = new HashSet<>();
        for (Class<? extends RawEntity<?>> entity : Arrays.<Class<? extends RawEntity<?>>>asList(
                JournalSession.class, JournalChangeSet.class, JournalOperation.class, JournalStep.class,
                JournalProgress.class, JournalProgressBatch.class, JournalBudget.class)) {
            // The Jira converter itself rejects identifiers longer than 30 characters.
            String table = names.getName(entity);
            assertTrue("Journal entities must not share a table: " + table, tables.add(table));
            assertTrue("Jira table identifier limit: " + table, table.length() <= 30);
        }
    }
}
