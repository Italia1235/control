package ru.rasulov.customfields.impl.requirement;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.project.Project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Screens of one project for the set_field_required preflight: by default every issue type has a DEFAULT-mapped
 * screen that contains every field; tests narrow that down per operation.
 */
public final class RequirementScreens {
    public final IssueTypeScreenSchemeManager manager = mock(IssueTypeScreenSchemeManager.class);
    public final IssueTypeScreenScheme top = mock(IssueTypeScreenScheme.class);
    public final FieldScreenScheme scheme = mock(FieldScreenScheme.class);
    private final List<FieldScreenSchemeItem> items = new ArrayList<FieldScreenSchemeItem>();

    public RequirementScreens() {
        when(manager.getIssueTypeScreenScheme(any(Project.class))).thenReturn(top);
        when(top.getEffectiveFieldScreenScheme(any(IssueType.class))).thenReturn(scheme);
        when(scheme.getFieldScreenSchemeItems()).thenAnswer(call -> (Collection<FieldScreenSchemeItem>) items);
        items.add(item(null, screen(1L, "Default Screen", true)));
    }

    public static FieldScreen screen(long id, String name, boolean containsEverything, String... fieldIds) {
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(id);
        when(screen.getName()).thenReturn(name);
        when(screen.containsField(anyString())).thenReturn(containsEverything);
        for (String fieldId : fieldIds) when(screen.containsField(fieldId)).thenReturn(true);
        return screen;
    }

    /** {@code operation == null} is the scheme's DEFAULT item. */
    public static FieldScreenSchemeItem item(com.atlassian.jira.issue.operation.ScreenableIssueOperation operation,
                                             FieldScreen screen) {
        FieldScreenSchemeItem item = mock(FieldScreenSchemeItem.class);
        when(item.getIssueOperation()).thenReturn(operation);
        when(item.getFieldScreen()).thenReturn(screen);
        return item;
    }

    public RequirementScreens defaultScreen(FieldScreen screen) {
        items.clear();
        items.add(item(null, screen));
        return this;
    }

    public RequirementScreens create(FieldScreen screen) {
        items.add(item(IssueOperations.CREATE_ISSUE_OPERATION, screen));
        return this;
    }

    public RequirementScreens edit(FieldScreen screen) {
        items.add(item(IssueOperations.EDIT_ISSUE_OPERATION, screen));
        return this;
    }
}
