package ru.rasulov.customfields.impl.screen;

import ru.rasulov.customfields.api.dto.screen.Recommendation;

import java.util.Collections;
import java.util.List;

/**
 * The plugin's advice for one screen, from three facts: is the field already there, is it the default screen, how
 * many projects outside the field's context share it. Advice only: the user decides, and a user who asks for the
 * default screen gets it.
 */
final class ScreenRecommender {

    private ScreenRecommender() {
    }

    static final class Advice {
        final Recommendation recommendation;
        final List<String> reasons;

        private Advice(Recommendation recommendation, String reason) {
            this.recommendation = recommendation;
            this.reasons = Collections.singletonList(reason);
        }
    }

    static Advice advise(boolean alreadyHasField, boolean defaultScreen, int projectsOutsideContext) {
        if (alreadyHasField) {
            return new Advice(Recommendation.ALREADY_PRESENT, "The field is already on this screen.");
        }
        if (defaultScreen) {
            return new Advice(Recommendation.NOT_RECOMMENDED, "This is the default screen, used by every project "
                    + "without a screen scheme of its own" + (projectsOutsideContext > 0
                    ? " (" + projectsOutsideContext + " outside the field's context)" : "")
                    + ". Add the field here only if the user asks for it.");
        }
        if (projectsOutsideContext > 0) {
            return new Advice(Recommendation.CAUTION, "The screen is shared with " + projectsOutsideContext
                    + " project(s) outside the field's context; adding the field changes the screen for them too.");
        }
        return new Advice(Recommendation.RECOMMENDED, "Only projects of the field's context use this screen.");
    }
}
