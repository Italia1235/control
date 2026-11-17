package ru.rasulov.common.impl.tools;

/**
 * Size bounds of the MCP transport. Over a bound the server answers with an explicit error; it never cuts a request or
 * a result short. Results of mutations are not bounded here: they are already bounded by the journal, and an apply
 * result that is lost to a limit would leave the caller unsure whether the change happened.
 */
public final class McpLimits {

    /** Characters of one POSTed body, single message or batch. */
    public static final int MAX_REQUEST_CHARS = 1_000_000;

    /** Messages in one JSON-RPC batch. */
    public static final int MAX_BATCH_MESSAGES = 20;

    /** Characters of the JSON text of a read tool's result. */
    public static final int MAX_READ_RESULT_CHARS = 500_000;

    private McpLimits() {
    }
}
