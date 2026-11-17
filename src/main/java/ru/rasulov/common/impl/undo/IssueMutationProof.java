package ru.rasulov.common.impl.undo;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import org.ofbiz.core.entity.GenericValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared proof across issue-type and workflow migration: same-millisecond edits must not look like our undo. */
public final class IssueMutationProof {
    private IssueMutationProof() { }

    /** Native facts read once for one issue, with the hash over all of them. */
    public static final class Proof {
        public final Long entryId;
        public final List<Long> stepIds;
        public final long changeCount;
        public final long historyCount;
        public final String hash;

        private Proof(Long entryId, List<Long> stepIds, long changeCount, long historyCount, String hash) {
            this.entryId = entryId;
            this.stepIds = Collections.unmodifiableList(stepIds);
            this.changeCount = changeCount;
            this.historyCount = historyCount;
            this.hash = hash;
        }
    }

    public static String hash(OfBizDelegator database, long issueId) {
        return read(database, issueId).hash;
    }

    /** One read of the issue row, its current steps and the two native counts; nothing is read twice. */
    public static Proof read(OfBizDelegator database, long issueId) {
        GenericValue issue = database.findById("Issue", issueId);
        if (issue == null) throw new IllegalStateException("Issue no longer exists: " + issueId);
        Map<String, Object> state = new TreeMap<String, Object>();
        state.put("issue", new TreeMap<String, Object>(issue.getAllFields()));
        Long entryId = issue.getLong("workflowId");
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        List<Long> stepIds = new ArrayList<Long>();
        long historyCount = 0L;
        if (entryId != null) {
            Map<String, Long> entry = Collections.singletonMap("entryId", entryId);
            for (GenericValue step : database.findByAnd("OSCurrentStep", entry, Collections.singletonList("id"))) {
                steps.add(new TreeMap<String, Object>(step.getAllFields()));
                stepIds.add(step.getLong("id"));
            }
            historyCount = NativeEntityCount.count(database, "OSHistoryStep", entry);
            state.put("historyCount", historyCount);
        } else {
            state.put("historyCount", 0L);
        }
        state.put("steps", steps);
        long changeCount = NativeEntityCount.count(database, "ChangeGroup", Collections.singletonMap("issue", issueId));
        state.put("changeCount", changeCount);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(JournalJson.write(state).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return new Proof(entryId, stepIds, changeCount, historyCount, result.toString());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
