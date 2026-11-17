package ru.rasulov.common.impl.screen;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.operation.ScreenableIssueOperation;

import java.util.Objects;

/** Which screen a screen scheme gives to CREATE/EDIT/VIEW; shared by screen and field-requirement features. */
public final class EffectiveScreens {
    private EffectiveScreens() {
    }

    public static boolean uses(FieldScreen screen, FieldScreenScheme scheme, String operation) {
        return Objects.equals(screen.getId(), effectiveScreen(scheme, operation).getId());
    }

    /** The screen the scheme gives to the operation: its own item, else the DEFAULT item. Never null; unreadable throws. */
    public static FieldScreen effectiveScreen(FieldScreenScheme scheme, String operation) {
        if (scheme == null) throw new IllegalStateException("No effective screen scheme.");
        ScreenableIssueOperation wanted = switch (operation) {
            case "CREATE" -> IssueOperations.CREATE_ISSUE_OPERATION;
            case "EDIT" -> IssueOperations.EDIT_ISSUE_OPERATION;
            case "VIEW" -> IssueOperations.VIEW_ISSUE_OPERATION;
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
        FieldScreen fallback = null;
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
            if (item.getIssueOperation() == null) fallback = item.getFieldScreen();
            else if (wanted.getId().equals(item.getIssueOperation().getId())) {
                FieldScreen effective = item.getFieldScreen();
                if (effective == null) throw new IllegalStateException("Could not read " + operation + " screen.");
                return effective;
            }
        }
        if (fallback == null) throw new IllegalStateException("No default screen for " + operation + ".");
        return fallback;
    }
}
