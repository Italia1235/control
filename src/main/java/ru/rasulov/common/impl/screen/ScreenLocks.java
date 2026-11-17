package ru.rasulov.common.impl.screen;

/** Shared cluster lock for all mutations of existing screen layouts. */
public final class ScreenLocks {
    public static final String EDIT_SCREENS = "ru.rasulov.control.edit-screens";
    private ScreenLocks() { }
}
