package ru.rasulov.customfields.impl.configuration;

/** One cluster lock for every write to field configurations, their schemes and project associations. */
public final class FieldConfigurationLocks {
    /** Kept equal to its historical value: the undo handler and running nodes know this name. */
    public static final String EDIT = "ru.rasulov.control.field-requirements";

    private FieldConfigurationLocks() { }
}
