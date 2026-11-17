package ru.rasulov.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One transition: the button the user presses to move an issue. Either {@code from} one status, or {@code fromAny}
 * from every status. Also the shape of a transition in the response.
 */
public final class TransitionDefinition {

    private final String name;
    private final String key;
    private final String from;
    private final boolean fromAny;
    private final String to;
    private final String screen;
    private final TransitionResolution resolution;

    public TransitionDefinition(String name, String from, boolean fromAny, String to) {
        this(name, from, fromAny, to, null);
    }

    public TransitionDefinition(String name, String from, boolean fromAny, String to, String screen) {
        this(name, from, fromAny, to, screen, null);
    }

    public TransitionDefinition(String name, String from, boolean fromAny, String to, String screen,
                                TransitionResolution resolution) {
        this(null, name, from, fromAny, to, screen, resolution);
    }

    @JsonCreator
    public TransitionDefinition(@JsonProperty("key") String key,
                                @JsonProperty("name") String name,
                                @JsonProperty("from") String from,
                                @JsonProperty("fromAny") boolean fromAny,
                                @JsonProperty("to") String to,
                                @JsonProperty("screen") String screen,
                                @JsonProperty("resolution") TransitionResolution resolution) {
        this.key = key;
        this.name = name;
        this.from = from;
        this.fromAny = fromAny;
        this.to = to;
        this.screen = screen;
        this.resolution = resolution;
    }

    @JsonProperty("key")
    public String getKey() { return key; }

    /** Name shown on the transition button. */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /** Name of the source status; {@code null} when {@code fromAny} is {@code true}. */
    @JsonProperty("from")
    public String getFrom() {
        return from;
    }

    /**
     * {@code true}: the transition is available from every status (a global transition, "all statuses to this
     * one"). Then {@code from} must be omitted. Exactly one of the two is given.
     */
    @JsonProperty("fromAny")
    public boolean isFromAny() {
        return fromAny;
    }

    /** Name of the target status. */
    @JsonProperty("to")
    public String getTo() {
        return to;
    }

    /**
     * Optional exact id or name of the existing Jira screen opened by this transition.
     * Same-plan new screens are declared by configure_workflow.newScreens and referenced by transitionRules.
     */
    @JsonProperty("screen")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getScreen() {
        return screen;
    }

    @JsonProperty("resolution")
    public TransitionResolution getResolution() {
        return resolution;
    }
}
