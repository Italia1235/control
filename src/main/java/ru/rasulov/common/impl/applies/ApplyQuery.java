package ru.rasulov.common.impl.applies;

import ru.rasulov.common.api.exception.InvalidRequestException;

/** Validated filters and paging of {@code GET /applies}. A blank parameter counts as absent. */
public final class ApplyQuery {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public final String actor;
    public final String tool;
    public final String state;
    public final String status;
    public final Long from;
    public final Long to;
    public final Long before;
    public final int limit;

    private ApplyQuery(String actor, String tool, String state, String status, Long from, Long to, Long before, int limit) {
        this.actor = actor;
        this.tool = tool;
        this.state = state;
        this.status = status;
        this.from = from;
        this.to = to;
        this.before = before;
        this.limit = limit;
    }

    /** @throws InvalidRequestException a number is not a number, {@code limit} is out of 1..200 or {@code from >= to} */
    public static ApplyQuery parse(String actor, String tool, String state, String status, String from, String to,
                                   String before, String limit) {
        Long fromValue = number("from", from);
        Long toValue = number("to", to);
        Long beforeValue = number("before", before);
        Long limitValue = number("limit", limit);
        if (limitValue != null && (limitValue < 1 || limitValue > MAX_LIMIT)) {
            throw new InvalidRequestException("'limit' must be between 1 and " + MAX_LIMIT + ".");
        }
        if (fromValue != null && toValue != null && fromValue >= toValue) {
            throw new InvalidRequestException("'from' must be less than 'to'.");
        }
        return new ApplyQuery(text(actor), text(tool), text(state), text(status), fromValue, toValue, beforeValue,
                limitValue == null ? DEFAULT_LIMIT : limitValue.intValue());
    }

    private static String text(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static Long number(String name, String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new InvalidRequestException("'" + name + "' must be an integer.");
        }
    }
}
