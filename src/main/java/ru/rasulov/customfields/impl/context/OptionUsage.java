package ru.rasulov.customfields.impl.context;

/** How many issues use one option: a number, or unknown with the reason. Informational only. */
public final class OptionUsage {
    private final Integer issues;
    private final String reason;

    private OptionUsage(Integer issues, String reason) { this.issues = issues; this.reason = reason; }

    public static OptionUsage count(long issues) {
        return new OptionUsage((int) Math.min(Integer.MAX_VALUE, Math.max(0, issues)), null);
    }
    public static OptionUsage unknown(String reason) { return new OptionUsage(null, reason); }

    public boolean isKnown() { return issues != null; }
    /** The number of issues; null when unknown. */
    public Integer getIssues() { return issues; }
    /** Why the number is unknown; null when it is known. */
    public String getReason() { return reason; }
}
