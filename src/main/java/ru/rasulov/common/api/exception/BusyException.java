package ru.rasulov.common.api.exception;

/**
 * Another call is creating fields and did not finish in time. Nothing was changed; the same request may be retried.
 */
public class BusyException extends ServiceException {

    public BusyException(String message) {
        super(message);
    }
}
