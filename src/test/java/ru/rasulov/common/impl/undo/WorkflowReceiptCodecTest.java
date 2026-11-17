package ru.rasulov.common.impl.undo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class WorkflowReceiptCodecTest {
    @Test public void tenScreensWithFiftyFieldsRetainEveryNativeBoundaryBeyondTheExpandedLimit() {
        Map<String, Object> receipt = boundary("APPLIED", "MATCH", "9223372036854775807");
        assertEquals(522, steps(receipt).size());
        assertTrue(WorkflowReceiptCodec.bytes(JournalJson.write(receipt)) > ChangeJournal.PROGRESS_JSON_LIMIT);
        assertTrue(WorkflowReceiptCodec.bytes(WorkflowReceiptCodec.write(receipt)) <= ChangeJournal.PROGRESS_JSON_LIMIT);
        assertTrue(WorkflowReceiptCodec.reservedBytes(receipt) <= ChangeJournal.PROGRESS_JSON_LIMIT);
        assertEquals(receipt, WorkflowReceiptCodec.read(WorkflowReceiptCodec.write(receipt)));
    }

    @Test public void everyFailurePrefixFitsItsReservationIncludingEscapedErrorsWithAndWithoutIds() {
        Map<String, Object> intent = boundary("NOT_ATTEMPTED", "NOT_CHECKED", null);
        int reserved = WorkflowReceiptCodec.reservedBytes(intent);
        Map<String, Object> receipt = boundary("NOT_ATTEMPTED", "NOT_CHECKED", null);
        List<Map<String, Object>> steps = steps(receipt);
        for (Map<String, Object> step : steps) {
            for (List<String> failure : List.of(List.of("UNKNOWN", "NOT_CHECKED"), List.of("FAILED", "NOT_CHECKED"),
                    List.of("APPLIED", "MISMATCH"), List.of("APPLIED", "UNAVAILABLE"))) {
                step.put("writeStatus", failure.get(0));
                step.put("verification", failure.get(1));
                step.put("error", "\u0000".repeat(WorkflowReceiptCodec.ERROR_LENGTH));
                assertFitsAndRoundTrips(receipt, reserved);
                step.put("id", "9".repeat(20));
                assertFitsAndRoundTrips(receipt, reserved);
                step.remove("id");
            }
            step.remove("error");
            step.put("writeStatus", "APPLIED");
            step.put("verification", "MATCH");
            step.put("id", "9".repeat(20));
        }
        assertFitsAndRoundTrips(receipt, reserved);
    }

    @Test public void everyKindStateAndEscapedUnicodeKeyIdAndErrorAreLossless() {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (String kind : List.of("STATUS", "SCREEN", "WORKFLOW_COPY", "DRAFT", "WORKFLOW_SAVE"))
            for (String write : List.of("NOT_ATTEMPTED", "UNKNOWN", "APPLIED", "FAILED"))
                for (String check : List.of("NOT_CHECKED", "MATCH", "MISMATCH", "UNAVAILABLE")) {
                    Map<String, Object> step = step("field:ключ\"\\\n\u0000:49", kind, write, check, "-9223372036854775808");
                    step.put("error", "Ошибка \"quoted\" \\ \n\t\u0000 \uD83D\uDE00");
                    steps.add(step);
                }
        Map<String, Object> receipt = Map.of("steps", steps);
        assertEquals(receipt, WorkflowReceiptCodec.read(WorkflowReceiptCodec.write(receipt)));
        assertEquals(Map.of("steps", List.of()), WorkflowReceiptCodec.read(WorkflowReceiptCodec.write(Map.of("steps", List.of()))));
    }

    @Test public void unfamiliarFieldsAndNullsAreNeverSilentlyDiscarded() {
        Map<String, Object> receipt = boundary("NOT_ATTEMPTED", "NOT_CHECKED", null);
        steps(receipt).get(0).put("futureField", List.of("preserve", "everything"));
        steps(receipt).get(1).put("id", null);
        assertEquals(receipt, WorkflowReceiptCodec.read(WorkflowReceiptCodec.write(receipt)));
        Map<String, Object> historical = Map.of("steps", List.of(step("screen:s0", "SCREEN", "UNKNOWN", "NOT_CHECKED", null)));
        assertEquals(historical, WorkflowReceiptCodec.read(JournalJson.write(historical)));
    }

    @Test public void longEscapedKeysCountAgainstEncodedStorageRatherThanAnInventedKeyLimit() {
        Map<String, Object> receipt = boundary("NOT_ATTEMPTED", "NOT_CHECKED", null);
        steps(receipt).get(0).put("key", "\u0000".repeat(ChangeJournal.PROGRESS_JSON_LIMIT));
        assertTrue(WorkflowReceiptCodec.reservedBytes(receipt) > ChangeJournal.PROGRESS_JSON_LIMIT);
        assertEquals(receipt, WorkflowReceiptCodec.read(WorkflowReceiptCodec.write(receipt)));
    }

    public static Map<String, Object> boundary(String write, String check, String id) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int screen = 0; screen < 10; screen++) {
            String key = "s" + screen;
            steps.add(step("screen:" + key, "SCREEN", write, check, id));
            steps.add(step("tab:" + key, "SCREEN", write, check, id));
            for (int field = 0; field < 50; field++) steps.add(step("field:" + key + ":" + field, "SCREEN", write, check, id));
        }
        steps.add(step("workflow-copy", "WORKFLOW_COPY", write, check, id));
        steps.add(step("workflow-save", "WORKFLOW_SAVE", write, check, id));
        return Map.of("steps", steps);
    }

    private static Map<String, Object> step(String key, String kind, String write, String check, String id) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("key", key); step.put("kind", kind); step.put("writeStatus", write); step.put("verification", check);
        if (id != null) step.put("id", id);
        return step;
    }

    @SuppressWarnings("unchecked") public static List<Map<String, Object>> steps(Map<String, Object> receipt) {
        return (List<Map<String, Object>>) receipt.get("steps");
    }

    private static void assertFitsAndRoundTrips(Map<String, Object> receipt, int reserved) {
        String stored = WorkflowReceiptCodec.write(receipt);
        assertTrue(WorkflowReceiptCodec.bytes(stored) <= reserved);
        assertEquals(receipt, WorkflowReceiptCodec.read(stored));
    }
}
