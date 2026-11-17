package ru.rasulov.customfields.api.dto.screen;

/** What the plugin suggests about adding the field to a screen. Only advice: the user decides. */
public enum Recommendation {
    /** Every project that uses the screen is in the context of the field. */
    RECOMMENDED,
    /** The screen is shared with projects outside the context; adding changes it for them too. */
    CAUTION,
    /** The default screen, used by every project without a screen scheme of its own. Only if the user asks for it. */
    NOT_RECOMMENDED,
    /** The field is already on the screen. */
    ALREADY_PRESENT
}
