package ru.rasulov.customfields.api.dto.screen;

import com.fasterxml.jackson.annotation.JsonProperty;

/** How many screens of the plan fall into each {@link Recommendation}; enough for a one-line message to the user. */
public final class ScreenSummary {

    private final int recommended;
    private final int caution;
    private final int notRecommended;
    private final int alreadyPresent;

    public ScreenSummary(int recommended, int caution, int notRecommended, int alreadyPresent) {
        this.recommended = recommended;
        this.caution = caution;
        this.notRecommended = notRecommended;
        this.alreadyPresent = alreadyPresent;
    }

    @JsonProperty("recommended")
    public int getRecommended() {
        return recommended;
    }

    @JsonProperty("caution")
    public int getCaution() {
        return caution;
    }

    @JsonProperty("notRecommended")
    public int getNotRecommended() {
        return notRecommended;
    }

    @JsonProperty("alreadyPresent")
    public int getAlreadyPresent() {
        return alreadyPresent;
    }
}
