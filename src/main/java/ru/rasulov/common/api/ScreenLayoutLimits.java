package ru.rasulov.common.api;

/** Bounds shared by screen layout mutations. */
public final class ScreenLayoutLimits {
    public static final int MAX_FIELDS_PER_CALL = CustomFieldCreationLimits.MAX_FIELDS_PER_CALL;
    public static final int MAX_SCREENS_PER_CALL = 100;
    public static final int MAX_PAIRS_PER_CALL = 500;
    private ScreenLayoutLimits() { }
}
