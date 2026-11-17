package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.avatar.Avatar;
import com.atlassian.jira.avatar.AvatarManager;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.ErrorCode;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.IssueTypeView;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.ItemResult;
import ru.rasulov.common.api.dto.issuetype.ItemStatus;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Resolves a whole batch of wanted global issue types against ONE read of Jira's catalog, before anything is planned
 * or written: create or reuse, duplicate keys and names, ambiguous or incompatible existing types, limits.
 * <p>
 * Pure decision logic with no plan store, lock or journal: {@link IssueTypeCreatorImpl} issues and consumes the plan
 * around it and {@link IssueTypeBatchWriter} executes it; a composite tool can resolve its types the same way
 * without calling another tool. An existing type is only ever reused as it is, never changed.
 */
@Named
public class IssueTypeBatchPlanner {
    public static final int MAX_ITEMS = 20;
    // Jira 9.12 ConstantsManager.validateName enforces this limit for issue type names.
    static final int MAX_NAME_LENGTH = 60;
    static final int MAX_KEY_LENGTH = 40;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private final IssueTypeManager manager;
    private final AvatarManager avatars;

    @Inject
    public IssueTypeBatchPlanner(@ComponentImport IssueTypeManager manager, @ComponentImport AvatarManager avatars) {
        this.manager = manager;
        this.avatars = avatars;
    }

    /** How one item will be treated. */
    enum Action { CREATE, REUSE, INVALID }

    /** One resolved item; {@code existing} is set for REUSE only. */
    static final class Item {
        final String key;
        final String name;
        final String description;
        final boolean subtask;
        final Action action;
        final IssueType existing;
        final ErrorCode errorCode;
        final String message;

        Item(String key, String name, String description, boolean subtask, Action action, IssueType existing,
             ErrorCode errorCode, String message) {
            this.key = key; this.name = name; this.description = description; this.subtask = subtask;
            this.action = action; this.existing = existing; this.errorCode = errorCode; this.message = message;
        }

        ItemResult preview() {
            switch (action) {
                case CREATE:
                    return new ItemResult(key, ItemStatus.WOULD_CREATE,
                            new IssueTypeView(null, name, description, subtask), null, null);
                case REUSE:
                    return new ItemResult(key, ItemStatus.EXISTS, IssueTypeView.of(existing),
                            "Existing type; reused as it is. Its name and description are not changed.", null);
                default:
                    return new ItemResult(key, ItemStatus.FAILED, null, message, errorCode);
            }
        }
    }

    /** The whole batch decision. */
    static final class Plan {
        final List<Item> items;
        /** Whole-catalog snapshot the decision was made on; part of the plan fingerprint. */
        final Map<String, IssueTypeView> snapshot;
        final Long avatarId;
        final ErrorCode errorCode;
        final String message;

        Plan(List<Item> items, Map<String, IssueTypeView> snapshot, Long avatarId, ErrorCode errorCode, String message) {
            this.items = items; this.snapshot = snapshot; this.avatarId = avatarId;
            this.errorCode = errorCode; this.message = message;
        }

        boolean valid() { return errorCode == null; }

        int creates() {
            int count = 0;
            for (Item item : items) if (item.action == Action.CREATE) count++;
            return count;
        }

        List<ItemResult> preview() {
            List<ItemResult> results = new ArrayList<ItemResult>();
            for (Item item : items) results.add(item.preview());
            return results;
        }

        /** What the fingerprint covers: every decision, the full catalog and the avatar used for new types. */
        List<Object> fingerprintSource() {
            List<Object> source = new ArrayList<Object>();
            source.add(preview());
            source.add(snapshot);
            source.add(avatarId);
            return source;
        }
    }

    /** Reads Jira once and resolves every item; validation problems are returned in the plan, not thrown. */
    Plan resolve(List<IssueTypeDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return rejected(new ArrayList<Item>(), ErrorCode.VALIDATION_ERROR,
                    "'issueTypes' must list at least one issue type.");
        }
        if (definitions.size() > MAX_ITEMS) {
            return rejected(new ArrayList<Item>(), ErrorCode.VALIDATION_ERROR,
                    "A batch holds at most " + MAX_ITEMS + " issue types.");
        }
        Collection<IssueType> catalog = manager.getIssueTypes();
        Map<String, IssueTypeView> snapshot = new TreeMap<String, IssueTypeView>();
        Map<String, List<IssueType>> byName = new HashMap<String, List<IssueType>>();
        for (IssueType type : catalog) {
            snapshot.put(type.getId(), IssueTypeView.of(type));
            if (type.getName() == null) continue;
            String name = type.getName().toLowerCase(Locale.ROOT);
            List<IssueType> same = byName.get(name);
            if (same == null) byName.put(name, same = new ArrayList<IssueType>());
            same.add(type);
        }
        Map<String, Integer> keyCounts = new HashMap<String, Integer>();
        Map<String, Integer> nameCounts = new HashMap<String, Integer>();
        for (IssueTypeDefinition definition : definitions) {
            if (definition == null) continue;
            count(keyCounts, trimmed(definition.getKey()));
            count(nameCounts, lower(trimmed(definition.getName())));
        }
        List<Item> items = new ArrayList<Item>();
        ErrorCode worst = null;
        String firstMessage = null;
        boolean anyCreate = false;
        int position = 0;
        for (IssueTypeDefinition definition : definitions) {
            position++;
            Item item = resolveOne(definition, position, keyCounts, nameCounts, byName);
            items.add(item);
            if (item.action == Action.INVALID) {
                if (worst == null || item.errorCode == ErrorCode.VALIDATION_ERROR) worst = item.errorCode;
                if (firstMessage == null) firstMessage = "'" + displayKey(item.key, position) + "': " + item.message;
            }
            if (item.action == Action.CREATE) anyCreate = true;
        }
        if (worst != null) {
            return new Plan(items, snapshot, null, worst, "The batch was not planned and nothing was changed. "
                    + "Fix every item marked FAILED and preview again. First problem: " + firstMessage);
        }
        Long avatarId = null;
        if (anyCreate) {
            avatarId = avatars.getDefaultAvatarId(Avatar.Type.ISSUETYPE);
            if (avatarId == null) {
                return new Plan(items, snapshot, null, ErrorCode.JIRA_ERROR, "Jira's default issue type avatar is "
                        + "unavailable. Nothing was changed; ask an administrator to check Jira.");
            }
        }
        return new Plan(items, snapshot, avatarId, null, null);
    }

    private static Item resolveOne(IssueTypeDefinition definition, int position, Map<String, Integer> keyCounts,
                                   Map<String, Integer> nameCounts, Map<String, List<IssueType>> byName) {
        if (definition == null) return invalid(null, null, position, "The item is empty.");
        String key = trimmed(definition.getKey());
        String name = trimmed(definition.getName());
        boolean subtask = Boolean.TRUE.equals(definition.isSubtask());
        if (key == null || key.isEmpty()) return invalid(key, name, position, "'key' is required: a short local label "
                + "of this item, unique in the request.");
        if (key.length() > MAX_KEY_LENGTH || !KEY.matcher(key).matches()) return invalid(key, name, position,
                "'key' must be 1-" + MAX_KEY_LENGTH + " letters, digits, '_', '.' or '-'.");
        if (keyCounts.get(key) > 1) return invalid(key, name, position, "The key is used by several items.");
        if (name == null || name.isEmpty()) return invalid(key, name, position, "'name' is required.");
        if (definition.isSubtask() == null) return invalid(key, name, position,
                "'subtask' must be set explicitly: false for a standard type, true for a sub-task type.");
        if (name.length() > MAX_NAME_LENGTH) return invalid(key, name, position,
                "Issue type names must not exceed " + MAX_NAME_LENGTH + " characters.");
        if (nameCounts.get(lower(name)) > 1) return invalid(key, name, position,
                "Another item has the same name (names are compared without case).");
        List<IssueType> same = byName.get(lower(name));
        if (same != null && same.size() > 1) return conflict(key, name, position,
                "Several existing issue types match this case-insensitive name. Resolve the ambiguity in Jira or ask "
                        + "for a different name; no existing type was changed.");
        if (same != null) {
            IssueType match = same.get(0);
            if (match.isSubTask() != subtask) return conflict(key, name, position,
                    "An issue type with this name already exists with a different standard/sub-task kind. Ask for a "
                            + "different name; the existing type was not changed.");
            return new Item(key, name, definition.getDescription(), subtask, Action.REUSE, match, null, null);
        }
        String description = definition.getDescription() == null ? "" : definition.getDescription();
        return new Item(key, name, description, subtask, Action.CREATE, null, null, null);
    }

    private static Item invalid(String key, String name, int position, String message) {
        return new Item(key, name, null, false, Action.INVALID, null, ErrorCode.VALIDATION_ERROR, message);
    }

    private static Item conflict(String key, String name, int position, String message) {
        return new Item(key, name, null, false, Action.INVALID, null, ErrorCode.NAME_CONFLICT, message);
    }

    private static Plan rejected(List<Item> items, ErrorCode code, String message) {
        return new Plan(items, null, null, code, message);
    }

    private static String displayKey(String key, int position) {
        return key == null || key.isEmpty() ? "item " + position : key;
    }

    private static void count(Map<String, Integer> counts, String value) {
        if (value == null || value.isEmpty()) return;
        Integer current = counts.get(value);
        counts.put(value, current == null ? 1 : current + 1);
    }

    private static String trimmed(String value) { return value == null ? null : value.trim(); }

    private static String lower(String value) { return value == null ? null : value.toLowerCase(Locale.ROOT); }
}
