package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenFactory;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse.CopyView;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Named
public class ScreenCloneWriter {
    private final FieldScreenFactory factory;
    private final FieldScreenManager screenManager;
    private final FieldScreenSchemeManager screenSchemeManager;
    private final IssueTypeScreenSchemeManager issueTypeScreenSchemeManager;

    @Inject
    public ScreenCloneWriter(@ComponentImport FieldScreenFactory factory,
                             @ComponentImport FieldScreenManager screenManager,
                             @ComponentImport FieldScreenSchemeManager screenSchemeManager,
                             @ComponentImport IssueTypeScreenSchemeManager issueTypeScreenSchemeManager) {
        this.factory = factory; this.screenManager = screenManager; this.screenSchemeManager = screenSchemeManager;
        this.issueTypeScreenSchemeManager = issueTypeScreenSchemeManager;
    }

    /**
     * Writes in a fixed order: screen copies, screen scheme copies, then either a new issue type screen scheme (filled, then
     * switched to the project last) or an in-place update of the project's own scheme. A failure is never lost: it is
     * rethrown as {@link Interrupted} carrying every copy known to exist, the stage, and the call whose outcome is unknown.
     */
    Written write(ScreenClonePlan plan) {
        Map<Long, FieldScreen> screenCopies = new HashMap<Long, FieldScreen>();
        Map<Long, FieldScreenScheme> schemeCopies = new HashMap<Long, FieldScreenScheme>();
        IssueTypeScreenScheme created = null;
        String stage = "copying screens";
        String inFlight = null;
        try {
            for (Map.Entry<FieldScreen, String> entry : plan.screens.entrySet()) {
                inFlight = entry.getValue();
                FieldScreen copy = copyScreen(entry.getKey(), entry.getValue());
                screenCopies.put(entry.getKey().getId(), copy);
                inFlight = null;
            }
            stage = "copying screen schemes";
            for (Map.Entry<FieldScreenScheme, String> entry : plan.screenSchemes.entrySet()) {
                inFlight = entry.getValue();
                FieldScreenScheme copy = copyScreenScheme(entry.getKey(), entry.getValue(), screenCopies);
                schemeCopies.put(entry.getKey().getId(), copy);
                inFlight = null;
            }
            IssueTypeScreenScheme target = plan.source;
            if (plan.newSchemeName != null) {
                stage = "creating the issue type screen scheme copy";
                inFlight = plan.newSchemeName;
                created = createTopScheme(plan);
                inFlight = null;
                stage = "filling the issue type screen scheme copy";
                populateTopScheme(plan, created, schemeCopies);
                stage = "switching the project to the copy";
                issueTypeScreenSchemeManager.addSchemeAssociation(plan.project, created);
                target = created;
            } else {
                stage = "updating the project's own issue type screen scheme";
                populateTopScheme(plan, target, schemeCopies);
            }
            return new Written(target, schemeCopies, screenCopies);
        } catch (RuntimeException failure) {
            throw new Interrupted(new Written(created, schemeCopies, screenCopies), stage, inFlight,
                    plan.newSchemeName != null, failure);
        }
    }

    /** A write failed after the call started; carries what is known to exist so the caller can report it. */
    static final class Interrupted extends RuntimeException {
        final Written written;
        private final String stage;
        private final String inFlight;
        private final boolean newScheme;

        Interrupted(Written written, String stage, String inFlight, boolean newScheme, RuntimeException cause) {
            super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
            this.written = written; this.stage = stage; this.inFlight = inFlight; this.newScheme = newScheme;
        }

        /** Something is known to exist, or the project's own scheme was being changed. */
        boolean partial() {
            return !newScheme || written.scheme != null || !written.screenSchemes.isEmpty() || !written.screens.isEmpty();
        }

        String explain() {
            StringBuilder text = new StringBuilder("Clone stopped while " + stage + ": " + getMessage() + ". ");
            if (inFlight != null) {
                text.append("The Jira call for '").append(inFlight).append("' was not confirmed, so that object may exist; ")
                        .append("look it up by name before any retry. ");
            }
            if (!newScheme) {
                text.append("Entries of the project's own issue type screen scheme may have been partly updated; read it. ");
            } else if (stage.startsWith("switching")) {
                text.append("Whether the project now uses the copy is not confirmed; read the project's issue type screen scheme. ");
            } else {
                text.append("The project was not switched and still uses its previous scheme. ");
            }
            return text.append("Nothing was rolled back; the objects listed in this result remain in Jira.").toString();
        }
    }

    private FieldScreen copyScreen(FieldScreen source, String name) {
        FieldScreen copy = factory.createScreen();
        copy.setName(name); copy.setDescription(source.getDescription());
        screenManager.createFieldScreen(copy);
        for (FieldScreenTab sourceTab : source.getTabs()) {
            FieldScreenTab tab = copy.addTab(sourceTab.getName());
            for (FieldScreenLayoutItem sourceItem : sourceTab.getFieldScreenLayoutItems()) {
                tab.addFieldScreenLayoutItem(sourceItem.getFieldId());
                FieldScreenLayoutItem item = tab.getFieldScreenLayoutItem(sourceItem.getFieldId());
                if (item != null && item.isShownWhenEmpty() != sourceItem.isShownWhenEmpty()) {
                    item.setShowWhenEmpty(sourceItem.isShownWhenEmpty()); item.store();
                }
            }
        }
        return copy;
    }

    private FieldScreenScheme copyScreenScheme(FieldScreenScheme source, String name,
                                                Map<Long, FieldScreen> screenCopies) {
        FieldScreenScheme copy = factory.createFieldScreenScheme();
        copy.setName(name); copy.setDescription(source.getDescription());
        screenSchemeManager.createFieldScreenScheme(copy);
        for (FieldScreenSchemeItem sourceItem : source.getFieldScreenSchemeItems()) {
            FieldScreenSchemeItem item = factory.createFieldScreenSchemeItem();
            item.setFieldScreenScheme(copy); item.setIssueOperation(sourceItem.getIssueOperation());
            FieldScreen replacement = screenCopies.get(sourceItem.getFieldScreenId());
            item.setFieldScreen(replacement == null ? sourceItem.getFieldScreen() : replacement);
            screenSchemeManager.createFieldScreenSchemeItem(item);
        }
        return copy;
    }

    private IssueTypeScreenScheme createTopScheme(ScreenClonePlan plan) {
        IssueTypeScreenScheme copy = factory.createIssueTypeScreenScheme();
        copy.setName(plan.newSchemeName); copy.setDescription(plan.source.getDescription());
        issueTypeScreenSchemeManager.createIssueTypeScreenScheme(copy);
        return copy;
    }

    private void populateTopScheme(ScreenClonePlan plan, IssueTypeScreenScheme targetScheme,
                                   Map<Long, FieldScreenScheme> copies) {
        Map<String, Boolean> explicit = new HashMap<String, Boolean>();
        for (IssueTypeScreenSchemeEntity sourceEntity : plan.source.getEntities()) {
            String typeId = sourceEntity.getIssueTypeId();
            if (typeId != null) explicit.put(typeId, true);
            FieldScreenScheme target = sourceEntity.getFieldScreenScheme();
            boolean selected = typeId != null && plan.selectedIssueTypeIds.contains(typeId);
            if (plan.scope == ru.rasulov.screens.api.dto.ScreenCloneScope.ALL || selected) {
                FieldScreenScheme replacement = copies.get(target.getId());
                if (replacement != null) target = replacement;
            }
            if (targetScheme != plan.source) {
                addEntity(targetScheme, typeId, target);
            } else if (target != sourceEntity.getFieldScreenScheme()) {
                sourceEntity.setFieldScreenScheme(target);
                issueTypeScreenSchemeManager.updateIssueTypeScreenSchemeEntity(sourceEntity);
            }
        }
        if (plan.scope == ru.rasulov.screens.api.dto.ScreenCloneScope.SELECTED) {
            for (com.atlassian.jira.issue.issuetype.IssueType type : plan.project.getIssueTypes()) {
                if (!plan.selectedIssueTypeIds.contains(type.getId()) || explicit.containsKey(type.getId())) continue;
                FieldScreenScheme sourceEffective = plan.source.getEffectiveFieldScreenScheme(type);
                FieldScreenScheme replacement = copies.get(sourceEffective.getId());
                addEntity(targetScheme, type.getId(), replacement);
            }
        }
    }

    private void addEntity(IssueTypeScreenScheme scheme, String typeId, FieldScreenScheme fieldScreenScheme) {
        IssueTypeScreenSchemeEntity entity = factory.createIssueTypeScreenSchemeEntity();
        entity.setIssueTypeScreenScheme(scheme); entity.setIssueTypeId(typeId);
        entity.setFieldScreenScheme(fieldScreenScheme);
        issueTypeScreenSchemeManager.createIssueTypeScreenSchemeEntity(entity);
    }

    static final class Written {
        final IssueTypeScreenScheme scheme;
        final Map<Long, FieldScreenScheme> screenSchemes;
        final Map<Long, FieldScreen> screens;
        Written(IssueTypeScreenScheme scheme, Map<Long, FieldScreenScheme> screenSchemes,
                Map<Long, FieldScreen> screens) {
            this.scheme = scheme; this.screenSchemes = new LinkedHashMap<Long, FieldScreenScheme>(screenSchemes);
            this.screens = new LinkedHashMap<Long, FieldScreen>(screens);
        }

        CopyView schemeView(CopyView planned) {
            return planned.getVerdict() == ru.rasulov.screens.api.dto.CloneProjectScreensResponse.Verdict.KEEP
                    ? planned : copied(planned, scheme.getId(), scheme.getName());
        }
        List<CopyView> screenSchemeViews(List<CopyView> planned) { return views(planned, screenSchemes); }
        List<CopyView> screenViews(List<CopyView> planned) { return views(planned, screens); }

        /** Only the copies known to exist; null when the issue type screen scheme copy was not created. */
        CopyView createdSchemeView(CopyView planned) {
            return scheme == null || planned.getVerdict() == ru.rasulov.screens.api.dto.CloneProjectScreensResponse.Verdict.KEEP
                    ? null : copied(planned, scheme.getId(), scheme.getName());
        }
        List<CopyView> createdScreenSchemeViews(List<CopyView> planned) { return created(views(planned, screenSchemes)); }
        List<CopyView> createdScreenViews(List<CopyView> planned) { return created(views(planned, screens)); }
        private static List<CopyView> created(List<CopyView> views) {
            List<CopyView> result = new ArrayList<CopyView>();
            for (CopyView view : views) if (view.getNewId() != null) result.add(view);
            return result;
        }
        private static List<CopyView> views(List<CopyView> planned, Map<Long, ?> copies) {
            List<CopyView> result = new ArrayList<CopyView>();
            for (CopyView view : planned) {
                Object copy = copies.get(view.getSourceId());
                if (copy instanceof FieldScreenScheme) {
                    FieldScreenScheme value = (FieldScreenScheme) copy;
                    result.add(copied(view, value.getId(), value.getName()));
                } else if (copy instanceof FieldScreen) {
                    FieldScreen value = (FieldScreen) copy;
                    result.add(copied(view, value.getId(), value.getName()));
                } else result.add(view);
            }
            return result;
        }
        private static CopyView copied(CopyView source, Long id, String name) {
            return new CopyView(source.getSourceId(), source.getSourceName(), id, name, source.getVerdict(),
                    source.getOtherProjects(), source.getOtherProjectNames());
        }
    }
}
