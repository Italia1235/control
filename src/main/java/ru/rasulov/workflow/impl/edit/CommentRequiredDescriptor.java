package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.DescriptorFactory;
import com.opensymphony.workflow.loader.ValidatorDescriptor;
import ru.rasulov.workflow.api.dto.edit.EditErrorCode;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ScriptRunner "Comment required" validator as it stands in a workflow descriptor: how it is recognized, whether a
 * transition has the screen it needs, and how it is added. Knows only the descriptor, never ScriptRunner.
 */
final class CommentRequiredDescriptor {

    static final String CANNED_SCRIPT = "com.onresolve.scriptrunner.canned.jira.workflow.validators.CommentRequiredValidator";

    private static final String ARG_FUNCTION_ID = "FIELD_FUNCTION_ID";
    private static final String ARG_CANNED = "canned-script";
    private static final String ARG_CLASS = "class.name";
    /** The arguments of the captured form: no other argument, no other type. */
    private static final Set<String> CAPTURED_ARGS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList(ARG_FUNCTION_ID, ARG_CANNED, ARG_CLASS)));

    private CommentRequiredDescriptor() { }

    /** True when the validator is exactly the captured form. */
    static boolean captured(ValidatorDescriptor validator) {
        Map<?, ?> args = validator.getArgs();
        if (!"class".equals(validator.getType())) return false;
        if (!CAPTURED_ARGS.equals(new LinkedHashSet<Object>(args.keySet()))) return false;
        if (!CANNED_SCRIPT.equals(args.get(ARG_CANNED))
                || !RequiredFieldsDescriptor.CLASS_NAME.equals(args.get(ARG_CLASS))) {
            return false;
        }
        return args.get(ARG_FUNCTION_ID) instanceof String && !((String) args.get(ARG_FUNCTION_ID)).isEmpty();
    }

    /**
     * True when the transition's screen meta is a number of a screen that exists. The layout of the screen is not
     * looked at.
     */
    static boolean screenFound(String screenMeta, FieldScreenManager screens) {
        if (screenMeta == null || screenMeta.trim().isEmpty()) return false;
        try {
            return screens.getFieldScreen(Long.parseLong(screenMeta.trim())) != null;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** True when the action has {@code view="fieldscreen"}: without it Jira never opens the transition screen. */
    static boolean hasScreenView(ActionDescriptor action) {
        return DescriptorFacts.SCREEN_VIEW.equals(action.getView());
    }

    /**
     * True when the transition has the screen ScriptRunner needs to ask for a comment: the screen meta is a number of
     * a screen that exists and the action has {@code view="fieldscreen"}.
     */
    static boolean screenUsable(ActionDescriptor action, FieldScreenManager screens) {
        return screenFound(DescriptorFacts.meta(action).get(DescriptorFacts.SCREEN_META), screens)
                && hasScreenView(action);
    }

    /**
     * Whether the action already has the validator in the captured form. Refuses a validator with the comment script in
     * any other form. Never changes the descriptor.
     */
    static boolean present(ActionDescriptor action) {
        boolean present = false;
        int position = 0;
        for (Object o : validators(action)) {
            ValidatorDescriptor validator = (ValidatorDescriptor) o;
            position++;
            if (captured(validator)) {
                present = true;
            } else if (CANNED_SCRIPT.equals(validator.getArgs().get(ARG_CANNED))) {
                throw new EditFailure(EditErrorCode.UNSUPPORTED_DESCRIPTOR, "The validator at position " + position
                        + " of this transition is a ScriptRunner Comment required validator in a form that was not "
                        + "captured (other or missing arguments, another type). Nothing is added, so as not to "
                        + "duplicate or damage it.");
            }
        }
        return present;
    }

    /** Adds one validator in the captured form at the end of the action's validators. */
    @SuppressWarnings("unchecked")
    static void apply(ActionDescriptor action, String functionId) {
        ValidatorDescriptor created = DescriptorFactory.getFactory().createValidatorDescriptor();
        created.setType("class");
        created.setName("");
        created.setParent(action);
        Map<String, String> args = created.getArgs();
        args.put(ARG_FUNCTION_ID, functionId);
        args.put(ARG_CANNED, CANNED_SCRIPT);
        args.put(ARG_CLASS, RequiredFieldsDescriptor.CLASS_NAME);
        validators(action).add(created);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> validators(ActionDescriptor action) {
        return (List<Object>) action.getValidators();
    }
}
