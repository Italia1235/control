package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import org.mockito.invocation.InvocationOnMock;
import org.ofbiz.core.entity.GenericValue;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * An in-memory CustomFieldOption table behind both views the code under test uses: Jira's {@link OptionsManager} (reads
 * and native writes) and the persisted rows of {@code OfBizDelegator}. A sequence set on an Option object is persisted only
 * by {@code updateOptions}, like Jira; every native write is logged in {@link #log}.
 */
final class OptionStoreFake {
    static final class Rec {
        final long id, configId;
        Long parent;
        long sequence;
        String value;
        boolean disabled;
        Long pendingSequence;
        Rec(long id, long configId, Long parent, long sequence, String value, boolean disabled) {
            this.id = id; this.configId = configId; this.parent = parent; this.sequence = sequence; this.value = value;
            this.disabled = disabled;
        }
    }

    final List<Rec> recs = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    long nextId = 1000;
    /** Native writes (create, setValue, enable, disable, updateOptions) counted so far. */
    int writes;
    /** The write with this number (1-based) throws before changing anything; 0 = never. */
    int failWrite;
    /** Writes are accepted but change nothing (a silently lost write). */
    boolean ignoreWrites;
    /** What a stored value becomes (a Jira-side normalisation); identity by default. */
    java.util.function.UnaryOperator<String> storeValue = value -> value;

    OptionStoreFake option(long id, long configId, Long parent, long sequence, String value, boolean disabled) {
        recs.add(new Rec(id, configId, parent, sequence, value, disabled));
        return this;
    }

    Rec rec(long id) {
        for (Rec rec : recs) if (rec.id == id) return rec;
        throw new IllegalArgumentException("no option " + id);
    }

    List<GenericValue> rows(long configId) {
        List<Rec> own = new ArrayList<>();
        for (Rec rec : recs) if (rec.configId == configId) own.add(rec);
        own.sort(Comparator.comparingLong((Rec rec) -> rec.id));
        List<GenericValue> rows = new ArrayList<>();
        for (Rec rec : own) {
            GenericValue row = mock(GenericValue.class);
            when(row.getLong("id")).thenReturn(rec.id);
            when(row.getLong("parentoptionid")).thenReturn(rec.parent);
            when(row.getLong("sequence")).thenReturn(rec.sequence);
            when(row.getString("value")).thenReturn(rec.value);
            when(row.getString("disabled")).thenReturn(rec.disabled ? "true" : "false");
            rows.add(row);
        }
        return rows;
    }

    /** A comparable text of every stored row of one config, for before/after comparisons. */
    String dump(long configId) {
        StringBuilder text = new StringBuilder();
        for (Rec rec : recs) if (rec.configId == configId)
            text.append(rec.id).append('|').append(rec.parent).append('|').append(rec.sequence).append('|')
                    .append(rec.value).append('|').append(rec.disabled).append('\n');
        return text.toString();
    }

    /** The values of one level in stored order. */
    List<String> level(long configId, Long parent) {
        List<Rec> own = new ArrayList<>();
        for (Rec rec : recs) if (rec.configId == configId && Objects.equals(rec.parent, parent)) own.add(rec);
        own.sort(Comparator.<Rec>comparingLong(rec -> rec.sequence).thenComparingLong(rec -> rec.id));
        List<String> values = new ArrayList<>();
        for (Rec rec : own) values.add(rec.value + (rec.disabled ? "!" : ""));
        return values;
    }

    Option optionObject(Rec rec) {
        return mock(Option.class, invocation -> {
            switch (invocation.getMethod().getName()) {
                case "getOptionId": return rec.id;
                case "getValue": return rec.value;
                case "getSequence": return rec.sequence;
                case "getDisabled": return rec.disabled;
                case "getParentOptionId": return rec.parent;
                case "getChildOptions": return childObjects(rec.id);
                case "setSequence": rec.pendingSequence = (Long) invocation.getArgument(0); return null;
                default: return RETURNS_DEFAULTS.answer(invocation);
            }
        });
    }

    private List<Option> childObjects(long parentId) {
        List<Rec> children = new ArrayList<>();
        for (Rec rec : recs) if (Long.valueOf(parentId).equals(rec.parent)) children.add(rec);
        children.sort(Comparator.<Rec>comparingLong(rec -> rec.sequence).thenComparingLong(rec -> rec.id));
        List<Option> result = new ArrayList<>();
        for (Rec rec : children) result.add(optionObject(rec));
        return result;
    }

    private boolean write(String entry) {
        writes++;
        if (failWrite == writes) throw new IllegalStateException("native option write failed: " + entry);
        log.add(entry);
        return !ignoreWrites;
    }

    /** Makes the mocks behave like Jira's option storage over this table. */
    void install(OptionsManager manager, OfBizDelegator db) {
        when(manager.getOptions(any(FieldConfig.class))).thenAnswer(call -> {
            long configId = ((FieldConfig) call.getArgument(0)).getId();
            List<Rec> roots = new ArrayList<>();
            for (Rec rec : recs) if (rec.configId == configId && rec.parent == null) roots.add(rec);
            roots.sort(Comparator.<Rec>comparingLong(rec -> rec.sequence).thenComparingLong(rec -> rec.id));
            List<Option> objects = new ArrayList<>();
            for (Rec rec : roots) objects.add(optionObject(rec));
            Options options = mock(Options.class);
            when(options.iterator()).thenAnswer(ignored -> objects.iterator());
            return options;
        });
        when(manager.findByOptionId(anyLong())).thenAnswer(call -> {
            for (Rec rec : recs) if (rec.id == (Long) call.getArgument(0)) return optionObject(rec);
            return null;
        });
        when(manager.createOption(any(FieldConfig.class), any(), anyLong(), anyString())).thenAnswer(call -> {
            FieldConfig config = call.getArgument(0);
            String value = call.getArgument(3);
            boolean apply = write("create " + call.getArgument(1) + ":" + call.getArgument(2) + ":" + value);
            if (!apply) return optionObject(new Rec(-1, config.getId(), null, 0, value, false));
            Rec rec = new Rec(nextId++, config.getId(), call.getArgument(1), call.getArgument(2), storeValue.apply(value), false);
            recs.add(rec);
            return optionObject(rec);
        });
        doAnswer(call -> {
            Long id = ((Option) call.getArgument(0)).getOptionId();
            if (write("setValue " + id + ":" + call.getArgument(1))) rec(id).value = storeValue.apply(call.getArgument(1));
            return null;
        }).when(manager).setValue(any(Option.class), anyString());
        doAnswer(call -> {
            Long id = ((Option) call.getArgument(0)).getOptionId();
            if (write("disable " + id)) rec(id).disabled = true;
            return null;
        }).when(manager).disableOption(any(Option.class));
        doAnswer(call -> {
            Long id = ((Option) call.getArgument(0)).getOptionId();
            if (write("enable " + id)) rec(id).disabled = false;
            return null;
        }).when(manager).enableOption(any(Option.class));
        doAnswer(call -> updateOptions(call)).when(manager).updateOptions(anyCollection());
        when(db.findByAnd(eq("CustomFieldOption"), anyMap(), anyList())).thenAnswer(call ->
                rows(Long.valueOf(String.valueOf(((Map<?, ?>) call.getArgument(1)).values().iterator().next()))));
    }

    private Object updateOptions(InvocationOnMock call) {
        Collection<?> options = call.getArgument(0);
        StringBuilder ids = new StringBuilder();
        for (Object option : options) ids.append(ids.length() == 0 ? "" : ",").append(((Option) option).getOptionId());
        boolean apply = write("updateOptions " + ids);
        for (Object option : options) {
            Rec rec = rec(((Option) option).getOptionId());
            if (apply && rec.pendingSequence != null) rec.sequence = rec.pendingSequence;
            rec.pendingSequence = null;
        }
        return null;
    }
}
