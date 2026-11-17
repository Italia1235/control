package ru.rasulov.common.api.exception;

/**
 * The request as a whole is unusable (malformed, empty, over a call-wide limit), as opposed to a single bad field
 * definition, which is reported in the response.
 */
public class InvalidRequestException extends ServiceException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
