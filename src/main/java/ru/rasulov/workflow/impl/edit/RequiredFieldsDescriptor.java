package ru.rasulov.workflow.impl.edit;

import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.DescriptorFactory;
import com.opensymphony.workflow.loader.ValidatorDescriptor;
import ru.rasulov.workflow.api.dto.edit.EditErrorCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ScriptRunner "Field(s) required" validator as it stands in a workflow descriptor: how it is recognized, which
 * fields it holds, and how fields are added to a transition. Knows only the descriptor, never ScriptRunner.
 */
final class RequiredFieldsDescriptor {

    static final String CANNED_SCRIPT = "com.onresolve.scriptrunner.canned.jira.workflow.validators.FieldRequiredValidator";
    static final String CLASS_NAME = "com.onresolve.jira.groovy.GroovyValidator";
    static final String SEPARATOR = "|||";

    private static final String ARG_FUNCTION_ID = "FIELD_FUNCTION_ID";
    private static final String ARG_FIELDS = "FIELD_REQUIRED_FIELDS";
    private static final String ARG_CANNED = "canned-script";
    private static final String ARG_CONDITION = "FIELD_CONDITION";
    private static final String ARG_CLASS = "class.name";
    /** The arguments of the captured form: no other argument, no other type. */
    private static final Set<String> CAPTURED_ARGS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList(ARG_FUNCTION_ID, ARG_FIELDS, ARG_CANNED, ARG_CONDITION, ARG_CLASS)));

    private RequiredFieldsDescriptor() { }

    /**
     * One validator of an action; {@code requiredFields} when it is a recognized unconditional or conditional one,
     * {@code commentRequired} when it is the ScriptRunner "Comment required" validator in the captured form.
     */
    record Entry(int position, ValidatorDescriptor validator, boolean requiredFields, boolean commentRequired,
                 List<String> fieldIds, boolean conditional, String type, String className, String cannedScript) { }

    /** Missing fields are always appended in a new validator; existing validators are never rewritten. */
    record Change(List<String> alreadyRequired, List<String> added, Integer position, List<String> resulting) { }

    /** The validators of an action in descriptor order, positions counted from 1. Never changes the descriptor. */
    static List<Entry> scan(ActionDescriptor action) {
        List<Entry> entries = new ArrayList<Entry>();
        int position = 0;
        for (Object o : validators(action)) {
            ValidatorDescriptor validator = (ValidatorDescriptor) o;
            position++;
            Map<?, ?> args = validator.getArgs();
            String canned = text(args.get(ARG_CANNED));
            String className = text(args.get(ARG_CLASS));
            List<String> fields = capturedFields(validator);
            if (fields != null) {
                entries.add(new Entry(position, validator, true, false, fields,
                        !text(args.get(ARG_CONDITION)).isEmpty(), validator.getType(), className, canned));
            } else {
                entries.add(new Entry(position, validator, false, CommentRequiredDescriptor.captured(validator),
                        Collections.<String>emptyList(), false, validator.getType(), className, canned));
            }
        }
        return entries;
    }

    /**
     * What happens when {@code requested} (unique, in request order) is added to the action: the fields that the
     * unconditional required-fields validators already hold together, the rest, and the validator that receives it.
     * Refuses a validator that is the required-fields one but not in the captured form. Never changes the descriptor.
     */
    static Change plan(ActionDescriptor action, List<String> requested) {
        List<Entry> entries = scan(action);
        Set<String> held = new LinkedHashSet<String>();
        for (Entry entry : entries) {
            if (!entry.requiredFields()) {
                if (CANNED_SCRIPT.equals(entry.cannedScript())) {
                    throw new EditFailure(EditErrorCode.UNSUPPORTED_DESCRIPTOR, "The validator at position "
                            + entry.position() + " of this transition is a ScriptRunner Field(s) required validator "
                            + "in a form that was not captured (other or missing arguments, an empty or malformed "
                            + "field list, another type). Nothing is added, so as not to duplicate or damage it.");
                }
                continue;
            }
            if (entry.conditional()) continue;
            held.addAll(entry.fieldIds());
        }
        List<String> already = new ArrayList<String>();
        List<String> added = new ArrayList<String>();
        for (String id : requested) {
            if (held.contains(id)) already.add(id);
            else added.add(id);
        }
        return new Change(already, added, null, List.copyOf(added));
    }

    /**
     * Appends one new validator holding only the missing fields. Existing validators keep their arguments and position.
     */
    @SuppressWarnings("unchecked")
    static void apply(ActionDescriptor action, Change change, String functionId) {
        if (change.added().isEmpty()) return;
        String joined = String.join(SEPARATOR, change.resulting());
        ValidatorDescriptor created = DescriptorFactory.getFactory().createValidatorDescriptor();
        created.setType("class");
        created.setName("");
        created.setParent(action);
        Map<String, String> args = created.getArgs();
        args.put(ARG_FUNCTION_ID, functionId);
        args.put(ARG_FIELDS, joined);
        args.put(ARG_CANNED, CANNED_SCRIPT);
        args.put(ARG_CONDITION, "");
        args.put(ARG_CLASS, CLASS_NAME);
        validators(action).add(created);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> validators(ActionDescriptor action) {
        return (List<Object>) action.getValidators();
    }

    /** The field ids when the validator is exactly the captured form, otherwise null. */
    private static List<String> capturedFields(ValidatorDescriptor validator) {
        Map<?, ?> args = validator.getArgs();
        if (!"class".equals(validator.getType())) return null;
        if (!CAPTURED_ARGS.equals(new LinkedHashSet<Object>(args.keySet()))) return null;
        if (!CANNED_SCRIPT.equals(args.get(ARG_CANNED)) || !CLASS_NAME.equals(args.get(ARG_CLASS))) return null;
        if (!(args.get(ARG_FIELDS) instanceof String) || !(args.get(ARG_CONDITION) instanceof String)
                || !(args.get(ARG_FUNCTION_ID) instanceof String)) {
            return null;
        }
        String joined = (String) args.get(ARG_FIELDS);
        if (joined.isEmpty()) return null;
        List<String> ids = new ArrayList<String>();
        int from = 0;
        while (true) {
            int at = joined.indexOf(SEPARATOR, from);
            String id = at < 0 ? joined.substring(from) : joined.substring(from, at);
            if (id.isEmpty() || id.indexOf('|') >= 0 || id.chars().anyMatch(Character::isWhitespace)) return null;
            ids.add(id);
            if (at < 0) break;
            from = at + SEPARATOR.length();
        }
        return Collections.unmodifiableList(ids);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
