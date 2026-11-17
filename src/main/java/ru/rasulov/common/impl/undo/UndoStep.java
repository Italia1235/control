package ru.rasulov.common.impl.undo;

/** Immutable durable intent/poststate, detached from an AO entity cache. */
public final class UndoStep {
    private final long id;
    private final String kind;
    private final String resource;
    private final String beforeJson;
    private final String afterJson;
    private final String state;

    public UndoStep(long id, String kind, String resource, String beforeJson, String afterJson, String state) {
        this.id = id;
        this.kind = kind;
        this.resource = resource;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.state = state;
    }

    public long getId() { return id; }
    public String getKind() { return kind; }
    public String getResource() { return resource; }
    public String getBeforeJson() { return beforeJson; }
    public String getAfterJson() { return afterJson; }
    public String getState() { return state; }
}
