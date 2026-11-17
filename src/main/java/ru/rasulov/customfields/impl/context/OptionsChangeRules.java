package ru.rasulov.customfields.impl.context;

import ru.rasulov.customfields.api.Limits;
import ru.rasulov.customfields.api.dto.context.OptionsChange;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsResponse.ErrorCode;
import ru.rasulov.customfields.impl.context.OptionTree.Node;

import java.util.*;

/**
 * Validates one options change against the current options and turns it into an {@link OptionsPlan}. No Jira access.
 * The change is applied as add, rename, enable/disable, order; the final state is validated as a whole before any write.
 */
final class OptionsChangeRules {
    private OptionsChangeRules() { }

    static final class Failure extends RuntimeException {
        final ErrorCode code;
        Failure(ErrorCode code, String message) { super(message); this.code = code; }
    }

    private static Failure fail(ErrorCode code, String message) { return new Failure(code, message); }

    /** An option in the final state; id is null for an option that does not exist yet. */
    private static final class State {
        final Long id, parentId;
        String value;
        boolean disabled;
        final boolean created;
        boolean renamed;
        State(Long id, Long parentId, String value, boolean disabled, boolean created) {
            this.id = id; this.parentId = parentId; this.value = value; this.disabled = disabled; this.created = created;
        }
    }

    /**
     * @param current        every option of the context (the context has exactly one FieldConfig)
     * @param defaultIds     ids of the default value's options, shown in the preview
     * @param allowChildren  true only for a cascading select
     * @throws Failure with the error code the user sees; nothing is written before the whole change is valid
     */
    static OptionsPlan plan(List<Node> current, OptionsChange change, Set<Long> defaultIds, boolean allowChildren) {
        if (change == null || !change.hasOperations())
            throw fail(ErrorCode.VALIDATION_ERROR, "options must name at least one of add, rename, disable, enable or order.");
        Map<Long, Node> byId = new LinkedHashMap<Long, Node>();
        for (Node node : current) byId.put(node.id, node);
        Map<Long, List<Node>> levels = OptionTree.levels(current);

        // 1. The shape of every entry and the values.
        for (OptionsChange.Add add : change.getAdd()) {
            if (add == null) throw fail(ErrorCode.VALIDATION_ERROR, "add must not contain empty entries.");
            valid(add.getValue());
        }
        for (OptionsChange.Rename rename : change.getRename()) {
            if (rename == null || rename.getOptionId() == null)
                throw fail(ErrorCode.VALIDATION_ERROR, "Every rename entry needs an optionId and a value.");
            valid(rename.getValue());
        }
        for (Long id : change.getDisable()) if (id == null) throw fail(ErrorCode.VALIDATION_ERROR, "disable must list optionIds.");
        for (Long id : change.getEnable()) if (id == null) throw fail(ErrorCode.VALIDATION_ERROR, "enable must list optionIds.");
        for (OptionsChange.Order order : change.getOrder()) {
            if (order == null) throw fail(ErrorCode.VALIDATION_ERROR, "order must not contain empty entries.");
            for (Long id : order.getOptionIds()) if (id == null) throw fail(ErrorCode.VALIDATION_ERROR, "order must list optionIds.");
        }

        // 2. One option once per list, and never both disabled and enabled.
        Set<Long> renameIds = new LinkedHashSet<Long>();
        for (OptionsChange.Rename rename : change.getRename())
            if (!renameIds.add(rename.getOptionId())) throw fail(ErrorCode.OPTION_CONFLICT, "Option " + rename.getOptionId() + " is renamed twice.");
        Set<Long> disableIds = once(change.getDisable(), "disable");
        Set<Long> enableIds = once(change.getEnable(), "enable");
        for (Long id : disableIds) if (enableIds.contains(id))
            throw fail(ErrorCode.OPTION_CONFLICT, "Option " + id + " is in both disable and enable.");
        for (OptionsChange.Order order : change.getOrder()) once(order.getOptionIds(), "order");

        // 3. Every named option belongs to this context.
        for (Long id : renameIds) known(byId, id);
        for (Long id : disableIds) known(byId, id);
        for (Long id : enableIds) known(byId, id);
        for (OptionsChange.Add add : change.getAdd()) {
            if (add.getParentOptionId() == null) continue;
            Node parent = known(byId, add.getParentOptionId());
            if (!allowChildren) throw fail(ErrorCode.VALIDATION_ERROR,
                    "Only a cascading select has child options; parentOptionId is not allowed for this field.");
            if (parent.parentId != null) throw fail(ErrorCode.VALIDATION_ERROR,
                    "Option " + parent.id + " is itself a child; a cascading select has two levels only.");
        }
        for (OptionsChange.Order order : change.getOrder()) {
            if (order.getParentOptionId() != null) known(byId, order.getParentOptionId());
            for (Long id : order.getOptionIds()) known(byId, id);
        }

        // 4. The order lists: each level once, complete, existing options of that level only.
        Map<Long, List<Long>> newOrder = new LinkedHashMap<Long, List<Long>>();
        List<String> unchanged = new ArrayList<String>();
        int orderedIds = 0;
        for (OptionsChange.Order order : change.getOrder()) {
            Long level = order.getParentOptionId();
            orderedIds += order.getOptionIds().size();
            if (newOrder.containsKey(level))
                throw fail(ErrorCode.OPTION_ORDER_INVALID, "The order of " + levelName(byId, level) + " is given twice.");
            List<Node> existing = levels.getOrDefault(level, Collections.<Node>emptyList());
            Set<Long> existingIds = new LinkedHashSet<Long>();
            for (Node node : existing) existingIds.add(node.id);
            for (Long id : order.getOptionIds()) if (!existingIds.contains(id))
                throw fail(ErrorCode.OPTION_ORDER_INVALID, "Option " + id + " ('" + byId.get(id).value + "') does not belong to "
                        + levelName(byId, level) + ".");
            List<String> missing = new ArrayList<String>();
            for (Node node : existing) if (!order.getOptionIds().contains(node.id)) missing.add("'" + node.value + "' (" + node.id + ")");
            if (!missing.isEmpty()) throw fail(ErrorCode.OPTION_ORDER_INVALID, "The order of " + levelName(byId, level)
                    + " must list every existing option, including disabled ones; missing: " + String.join(", ", missing) + ".");
            newOrder.put(level, new ArrayList<Long>(order.getOptionIds()));
        }

        // 5. The final state: add, rename, enable/disable.
        Map<Long, State> states = new LinkedHashMap<Long, State>();
        for (Node node : current) states.put(node.id, new State(node.id, node.parentId, node.value, node.disabled, false));
        List<State> created = new ArrayList<State>();
        for (OptionsChange.Add add : change.getAdd())
            created.add(new State(null, add.getParentOptionId(), add.getValue().trim(), false, true));
        Map<Long, String> renames = new LinkedHashMap<Long, String>();
        for (OptionsChange.Rename rename : change.getRename()) {
            State state = states.get(rename.getOptionId());
            String value = rename.getValue().trim();
            if (value.equals(state.value)) { unchanged.add("без изменений: " + describe(state) + " уже называется так же"); continue; }
            state.value = value; state.renamed = true;
            renames.put(state.id, value);
        }
        List<Long> disables = new ArrayList<Long>(), enables = new ArrayList<Long>();
        for (Long id : disableIds) {
            State state = states.get(id);
            if (state.disabled) { unchanged.add("без изменений: " + describe(state) + " уже отключена"); continue; }
            state.disabled = true; disables.add(id);
        }
        for (Long id : enableIds) {
            State state = states.get(id);
            if (!state.disabled) { unchanged.add("без изменений: " + describe(state) + " уже включена"); continue; }
            state.disabled = false; enables.add(id);
        }
        Map<Long, List<Long>> realOrder = new LinkedHashMap<Long, List<Long>>();
        for (Map.Entry<Long, List<Long>> entry : newOrder.entrySet()) {
            List<Long> currentOrder = new ArrayList<Long>();
            for (Node node : levels.getOrDefault(entry.getKey(), Collections.<Node>emptyList())) currentOrder.add(node.id);
            if (currentOrder.equals(entry.getValue())) unchanged.add("без изменений: порядок " + levelName(byId, entry.getKey()) + " уже такой");
            else realOrder.put(entry.getKey(), entry.getValue());
        }

        // 6. Cascade rules on the final state.
        for (State add : created) {
            if (add.parentId != null && states.get(add.parentId).disabled)
                throw fail(ErrorCode.OPTION_PARENT_DISABLED, "Cannot add '" + add.value + "' under the disabled option "
                        + describe(states.get(add.parentId)) + ".");
        }
        for (Long id : enables) {
            State state = states.get(id);
            if (state.parentId != null && states.get(state.parentId).disabled)
                throw fail(ErrorCode.OPTION_PARENT_DISABLED, "Cannot enable " + describe(state) + " while its parent "
                        + describe(states.get(state.parentId)) + " is disabled; enable the parent in the same request.");
        }
        for (Long id : disables) {
            List<String> enabledChildren = new ArrayList<String>();
            for (Node child : levels.getOrDefault(id, Collections.<Node>emptyList()))
                if (!states.get(child.id).disabled) enabledChildren.add("'" + states.get(child.id).value + "' (" + child.id + ")");
            if (!enabledChildren.isEmpty()) throw fail(ErrorCode.OPTION_PARENT_HAS_ENABLED_CHILDREN, "Cannot disable "
                    + describe(states.get(id)) + " while its child options are enabled: " + String.join(", ", enabledChildren)
                    + ". Disable them in the same request.");
        }

        // 7. No duplicate value on one level of the final state (disabled options and case included); only a level that the
        //    request touched with a new or renamed option is judged, so that old data is never blamed on the request.
        Map<Long, Map<String, List<State>>> byLevel = new LinkedHashMap<Long, Map<String, List<State>>>();
        List<State> all = new ArrayList<State>(states.values());
        all.addAll(created);
        for (State state : all)
            byLevel.computeIfAbsent(state.parentId, key -> new LinkedHashMap<String, List<State>>())
                    .computeIfAbsent(String.valueOf(state.value).toLowerCase(Locale.ROOT), key -> new ArrayList<State>()).add(state);
        for (Map<String, List<State>> level : byLevel.values()) for (List<State> same : level.values()) {
            if (same.size() < 2) continue;
            for (State state : same) if (state.created || state.renamed)
                throw fail(ErrorCode.OPTION_DUPLICATE, "The value '" + state.value + "' would occur more than once among the options of the same level"
                        + (state.parentId == null ? "" : " under " + describe(states.get(state.parentId))) + ".");
        }

        // 8. Limits on the final state, only where the request adds: a context or level that is already over a limit
        //    (made in the Jira UI) stays editable without an add, and an add into it is refused.
        if (!created.isEmpty()) {
            int roots = 0;
            Map<Long, Integer> children = new HashMap<Long, Integer>();
            for (State state : all) {
                if (state.parentId == null) roots++;
                else children.merge(state.parentId, 1, Integer::sum);
            }
            Set<Long> addedLevels = new HashSet<Long>();
            for (State add : created) addedLevels.add(add.parentId);
            if (addedLevels.contains(null) && roots > Limits.MAX_ROOT_OPTIONS)
                throw fail(ErrorCode.OPTIONS_LIMIT, "At most " + Limits.MAX_ROOT_OPTIONS + " top-level options are allowed; the result would have " + roots + ".");
            for (Long parent : addedLevels) if (parent != null && children.get(parent) > Limits.MAX_CHILD_OPTIONS_PER_PARENT)
                throw fail(ErrorCode.OPTIONS_LIMIT, "At most " + Limits.MAX_CHILD_OPTIONS_PER_PARENT + " child options per parent are allowed; "
                        + describe(states.get(parent)) + " would have " + children.get(parent) + ".");
            if (all.size() > Limits.MAX_OPTIONS_PER_CONTEXT)
                throw fail(ErrorCode.OPTIONS_LIMIT, "At most " + Limits.MAX_OPTIONS_PER_CONTEXT + " options per context are allowed; the result would have " + all.size() + ".");
        }

        // 9. Sequences: an ordered level is renumbered from 0; new options follow the existing options of their level.
        Map<Long, Long> sequences = new LinkedHashMap<Long, Long>();
        Map<Long, Integer> positionBefore = new HashMap<Long, Integer>(), positionAfter = new HashMap<Long, Integer>();
        for (Map.Entry<Long, List<Node>> level : levels.entrySet()) {
            for (int i = 0; i < level.getValue().size(); i++) positionBefore.put(level.getValue().get(i).id, i);
        }
        Map<Long, List<Long>> touchedLevels = new LinkedHashMap<Long, List<Long>>();
        List<Long> levelKeys = new ArrayList<Long>(levels.keySet());
        for (State add : created) if (!levelKeys.contains(add.parentId)) levelKeys.add(add.parentId);
        List<OptionsPlan.Add> adds = new ArrayList<OptionsPlan.Add>();
        Map<State, Integer> positionOfNew = new IdentityHashMap<State, Integer>();
        for (Long level : levelKeys) {
            List<Node> existing = levels.getOrDefault(level, Collections.<Node>emptyList());
            List<Long> finalOrder = new ArrayList<Long>();
            long maxSequence = -1;
            if (realOrder.containsKey(level)) {
                finalOrder.addAll(realOrder.get(level));
                for (int i = 0; i < finalOrder.size(); i++) {
                    Long id = finalOrder.get(i);
                    if (byId.get(id).sequence != i) sequences.put(id, (long) i);
                }
                maxSequence = finalOrder.size() - 1;
            } else {
                for (Node node : existing) { finalOrder.add(node.id); maxSequence = Math.max(maxSequence, node.sequence); }
            }
            int index = finalOrder.size();
            long next = maxSequence + 1;
            boolean hasNew = false;
            for (State add : created) if (Objects.equals(add.parentId, level)) {
                positionOfNew.put(add, index++);
                adds.add(new OptionsPlan.Add(level, add.value, next++));
                hasNew = true;
            }
            for (int i = 0; i < finalOrder.size(); i++) positionAfter.put(finalOrder.get(i), i);
            if (hasNew || realOrder.containsKey(level)) touchedLevels.put(level, finalOrder);
        }

        // 10. The preview rows (existing options in their stored order, then the new ones) and the warnings.
        List<OptionsPlan.Row> rows = new ArrayList<OptionsPlan.Row>();
        boolean anyRename = !renames.isEmpty(), anyDisable = !disables.isEmpty(), disablesDefault = false;
        for (Node node : current) {
            State state = states.get(node.id);
            List<String> changes = new ArrayList<String>();
            if (state.renamed) changes.add("RENAMED");
            if (disables.contains(node.id)) changes.add("DISABLED");
            if (enables.contains(node.id)) changes.add("ENABLED");
            int was = positionBefore.get(node.id), now = positionAfter.get(node.id);
            if (realOrder.containsKey(node.parentId) && was != now) changes.add("MOVED");
            if (changes.isEmpty()) continue;
            boolean isDefault = defaultIds.contains(node.id);
            if (isDefault && changes.contains("DISABLED")) disablesDefault = true;
            StringBuilder summary = new StringBuilder(state.renamed ? node.value + " → " + state.value : node.value);
            if (changes.contains("DISABLED")) summary.append(", отключена");
            if (changes.contains("ENABLED")) summary.append(", включена");
            if (changes.contains("MOVED")) summary.append(", позиция ").append(was).append(" → ").append(now);
            rows.add(new OptionsPlan.Row(node.id, node.parentId, changes, optionView(node.value, node.disabled, was, null),
                    optionView(state.value, state.disabled, now, null), isDefault, summary.toString()));
        }
        for (State add : created) {
            int position = positionOfNew.get(add);
            rows.add(new OptionsPlan.Row(null, add.parentId, Collections.singletonList("ADDED"), null,
                    optionView(add.value, false, position, add.parentId), false,
                    "добавлена: " + add.value + ", позиция " + position));
        }
        List<Map<String, Object>> warnings = new ArrayList<Map<String, Object>>();
        if (anyRename) warnings.add(warning("RENAME_CHANGES_ALL_ISSUES", "Renaming changes how the value is displayed in every issue that uses it "
                + "and can break filters and JQL that match the old text."));
        if (anyDisable) warnings.add(warning("DISABLE_KEEPS_ISSUE_VALUES", "A disabled option can no longer be chosen, but the values already "
                + "stored in issues are kept."));
        if (disablesDefault) warnings.add(warning("DEFAULT_OPTION_DISABLED", "An option that is the default value of this context is disabled; "
                + "this tool does not change the default."));

        // The state to verify after the write.
        Map<Long, OptionsPlan.Expected> expected = new LinkedHashMap<Long, OptionsPlan.Expected>();
        for (State state : states.values()) expected.put(state.id, new OptionsPlan.Expected(state.parentId, state.value, state.disabled));
        int touched = change.getAdd().size() + change.getRename().size() + change.getDisable().size() + change.getEnable().size() + orderedIds;
        return new OptionsPlan(adds, renames, disables, enables, sequences, unchanged, rows, warnings, touched, current, expected, touchedLevels);
    }

    private static void valid(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw fail(ErrorCode.OPTION_VALUE_INVALID, "An option value must not be empty.");
        if (trimmed.length() > Limits.MAX_OPTION_LENGTH)
            throw fail(ErrorCode.OPTION_VALUE_INVALID, "An option value must be at most " + Limits.MAX_OPTION_LENGTH + " characters.");
    }

    private static Set<Long> once(List<Long> ids, String list) {
        Set<Long> seen = new LinkedHashSet<Long>();
        for (Long id : ids) if (!seen.add(id)) throw fail(ErrorCode.OPTION_CONFLICT, "Option " + id + " is listed twice in " + list + ".");
        return seen;
    }

    private static Node known(Map<Long, Node> byId, Long id) {
        Node node = byId.get(id);
        if (node == null) throw fail(ErrorCode.OPTION_NOT_IN_CONTEXT, "Option " + id + " is not an option of this context. "
                + "Read the options with get_field_contexts and includeOptions=true.");
        return node;
    }

    private static String levelName(Map<Long, Node> byId, Long level) {
        return level == null ? "the top-level options" : "the child options of '" + byId.get(level).value + "' (" + level + ")";
    }

    private static String describe(State state) { return "'" + state.value + "' (" + state.id + ")"; }

    private static Map<String, Object> optionView(String value, boolean disabled, int position, Long parentId) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("value", value); view.put("disabled", disabled); view.put("position", position);
        if (parentId != null) view.put("parentOptionId", parentId);
        return view;
    }

    private static Map<String, Object> warning(String code, String message) {
        Map<String, Object> warning = new LinkedHashMap<String, Object>();
        warning.put("code", code); warning.put("message", message);
        return warning;
    }
}
