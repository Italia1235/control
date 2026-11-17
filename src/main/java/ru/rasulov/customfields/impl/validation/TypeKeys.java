package ru.rasulov.customfields.impl.validation;

/** Keys of Jira's built-in custom field types that the rules refer to. */
public final class TypeKeys {

    /** Plugin key of the built-in types; a short type key such as {@code textfield} is expanded with it. */
    public static final String SYSTEM_PLUGIN = "com.atlassian.jira.plugin.system.customfieldtypes";

    /** The only type whose options may have children. */
    public static final String CASCADING_SELECT = SYSTEM_PLUGIN + ":cascadingselect";

    /** Exact Jira 9.12 built-in modules whose issue values use native Jira storage.
     * Unknown keys, including future modules under the same plugin prefix, are not evidence of safe deletion.
     */
    private static final java.util.Set<String> NATIVE_STORED_TYPES = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    SYSTEM_PLUGIN + ":textfield", SYSTEM_PLUGIN + ":textarea",
                    SYSTEM_PLUGIN + ":datepicker", SYSTEM_PLUGIN + ":datetime", SYSTEM_PLUGIN + ":float",
                    SYSTEM_PLUGIN + ":importid", SYSTEM_PLUGIN + ":readonlyfield",
                    SYSTEM_PLUGIN + ":select", SYSTEM_PLUGIN + ":radiobuttons", SYSTEM_PLUGIN + ":multiselect",
                    SYSTEM_PLUGIN + ":multicheckboxes", CASCADING_SELECT,
                    SYSTEM_PLUGIN + ":project", SYSTEM_PLUGIN + ":version", SYSTEM_PLUGIN + ":multiversion",
                    SYSTEM_PLUGIN + ":userpicker", SYSTEM_PLUGIN + ":multiuserpicker",
                    SYSTEM_PLUGIN + ":grouppicker", SYSTEM_PLUGIN + ":multigrouppicker", SYSTEM_PLUGIN + ":url",
                    SYSTEM_PLUGIN + ":labels")));

    public static boolean hasNativeValueStorage(String key) {
        return NATIVE_STORED_TYPES.contains(key);
    }

    /**
     * Built-in types usable with Jira's initial context configuration and no explicit additional setup.
     * Optional config items exist (e.g. user filters and version order), but are not copied.
     * Select-like types need options ({@link #contextTakesOptions}), plugin types need their own settings;
     * unknown keys are not admitted.
     */
    private static final java.util.Set<String> CONTEXT_WITHOUT_SETTINGS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    SYSTEM_PLUGIN + ":textfield", SYSTEM_PLUGIN + ":textarea",
                    SYSTEM_PLUGIN + ":datepicker", SYSTEM_PLUGIN + ":datetime", SYSTEM_PLUGIN + ":float",
                    SYSTEM_PLUGIN + ":url", SYSTEM_PLUGIN + ":labels",
                    SYSTEM_PLUGIN + ":userpicker", SYSTEM_PLUGIN + ":multiuserpicker",
                    SYSTEM_PLUGIN + ":grouppicker", SYSTEM_PLUGIN + ":multigrouppicker",
                    SYSTEM_PLUGIN + ":project", SYSTEM_PLUGIN + ":version", SYSTEM_PLUGIN + ":multiversion")));

    public static boolean contextNeedsNoSettings(String key) {
        return CONTEXT_WITHOUT_SETTINGS.contains(key);
    }

    /** Built-in select-like types whose new context is usable once it receives its own explicit option list. */
    private static final java.util.Set<String> CONTEXT_WITH_OPTIONS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    SYSTEM_PLUGIN + ":select", SYSTEM_PLUGIN + ":radiobuttons", SYSTEM_PLUGIN + ":multiselect",
                    SYSTEM_PLUGIN + ":multicheckboxes", CASCADING_SELECT)));

    public static boolean contextTakesOptions(String key) {
        return CONTEXT_WITH_OPTIONS.contains(key);
    }

    private TypeKeys() {
    }
}
