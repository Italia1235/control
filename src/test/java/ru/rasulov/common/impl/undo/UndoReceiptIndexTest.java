package ru.rasulov.common.impl.undo;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class UndoReceiptIndexTest {
    @Test public void inverseOrderNotTimestampChoosesTheProofForTheOlderTransition() {
        UndoReceiptIndex receipts = new UndoReceiptIndex();
        String latestHash = ChangeJournalDatabaseTest.repeat('b', 64);
        receipts.add(10L, receipt(42L, 1000L, ChangeJournalDatabaseTest.repeat('a', 64)));
        receipts.add(9L, receipt(42L, 700L, latestHash));
        UndoReceipt proof = receipts.after(8L).get(42L);
        assertEquals(700L, proof.getUpdated());
        assertEquals(latestHash, proof.getStateHash());
        assertEquals(1000L, receipts.after(9L).get(42L).getUpdated());
        assertNull(receipts.after(10L).get(42L));
    }

    @Test public void previouslyUndoneRequestCannotShadowProofOfANewerSelectedChain() {
        UndoReceiptIndex receipts = new UndoReceiptIndex();
        receipts.add(3L, receipt(42L, 9000L, ChangeJournalDatabaseTest.repeat('a', 64)));
        String currentHash = ChangeJournalDatabaseTest.repeat('c', 64);
        receipts.add(20L, receipt(42L, 100L, currentHash));
        Map<Long, UndoReceipt> selected = receipts.after(15L);
        assertEquals(currentHash, selected.get(42L).getStateHash());
        assertEquals(100L, selected.get(42L).getUpdated());
        assertNull(selected.get(43L));
    }

    private static String receipt(long id, long updated, String hash) {
        return JournalJson.write(Collections.singletonMap("issues", Collections.singletonList(Arrays.asList(id, updated, hash))));
    }
}
