package ru.rasulov.common.api.exception;

/**
 * Base of the failures that {@code CustomFieldCreator} reports for a call as a whole. Failures of a single field are
 * not exceptions: they are {@code FAILED} entries in the response.
 */
public abstract class ServiceException extends RuntimeException {

    protected ServiceException(String message) {
        super(message);
    }
}
