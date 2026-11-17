package ru.rasulov.customfields.impl.assets;

import com.atlassian.jira.user.ApplicationUser;
import ru.rasulov.common.impl.undo.UndoHandler;
import ru.rasulov.common.impl.undo.UndoStep;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.Collections;
import java.util.List;

/** Only restores settings; never deletes external Assets issue data or rewrites journal rows. */
@Named
public class AssetsConfigurationUndoHandler implements UndoHandler {
    private final AssetsFieldConfiguration assets;
    @Inject
    public AssetsConfigurationUndoHandler(AssetsFieldConfiguration assets) { this.assets = assets; }
    public String kind() { return "cf.assets"; }
    public List<String> lockNames(UndoStep step) {
        return Collections.singletonList("ru.rasulov.control.create-custom-fields");
    }
    public String conflict(UndoStep step) {
        try {
            String current = assets.snapshot(assets.configId(step.getBeforeJson()));
            if (sameState(current, step.getBeforeJson())) return null;
            if (step.getAfterJson() == null) return "Uncertain Assets configuration mutation: no persisted poststate";
            if (!assets.initialized(step.getBeforeJson()))
                return "Cannot safely delete newly-created external Assets configuration; field and settings are retained";
            return sameState(current, step.getAfterJson()) ? null : "Assets field configuration changed after this operation";
        } catch (RuntimeException e) { return "Cannot prove Assets configuration state: " + e.getMessage(); }
    }
    public void undo(UndoStep step, ApplicationUser actor) {
        String conflict = conflict(step);
        if (conflict != null) throw new IllegalStateException(conflict);
        if (!sameState(assets.snapshot(assets.configId(step.getBeforeJson())), step.getBeforeJson()))
            assets.restore(step.getBeforeJson());
    }
}
