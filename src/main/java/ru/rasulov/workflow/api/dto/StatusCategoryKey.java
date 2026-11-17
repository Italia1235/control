package ru.rasulov.workflow.api.dto;

/**
 * The three categories a new status can have; they colour the status and decide where an issue counts as to do, in
 * progress or done. The user decides which one; the model asks when the text does not say. {@link #jiraKey()} is the key Jira knows the category by.
 */
public enum StatusCategoryKey {

    TO_DO("new"),
    IN_PROGRESS("indeterminate"),
    DONE("done");

    private final String jiraKey;

    StatusCategoryKey(String jiraKey) {
        this.jiraKey = jiraKey;
    }

    /** {@code new}, {@code indeterminate} or {@code done}. */
    public String jiraKey() {
        return jiraKey;
    }

    /**
     * @param text a name from this enum, in any case and with {@code -} or space instead of {@code _}
     * @return {@code null} when the text names none
     */
    public static StatusCategoryKey parse(String text) {
        if (text == null) {
            return null;
        }
        String wanted = text.trim().replace('-', '_').replace(' ', '_');
        for (StatusCategoryKey key : values()) {
            if (key.name().equalsIgnoreCase(wanted)) {
                return key;
            }
        }
        return null;
    }

    /**
     * @return the category for a Jira category key, or {@code null} for anything else (Jira also has
     *         {@code undefined}, which this service never creates)
     */
    public static StatusCategoryKey fromJiraKey(String jiraKey) {
        for (StatusCategoryKey key : values()) {
            if (key.jiraKey.equals(jiraKey)) {
                return key;
            }
        }
        return null;
    }
}
