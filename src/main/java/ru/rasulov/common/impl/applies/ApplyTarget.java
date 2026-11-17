package ru.rasulov.common.impl.applies;

/** What an apply touched: a type, a human label and an optional id. */
public final class ApplyTarget {
    public final String type;
    public final String label;
    public final String id;

    public ApplyTarget(String type, String label, String id) {
        this.type = type;
        this.label = label;
        this.id = id;
    }
}
