package ru.rasulov.common.api.exception;

/**
 * The caller may not use the service: either not logged in or lacks the global ADMINISTER permission.
 */
public class AccessException extends ServiceException {

    private final boolean anonymous;

    public AccessException(String message, boolean anonymous) {
        super(message);
        this.anonymous = anonymous;
    }

    /**
     * @return {@code true} if there is no authenticated user, {@code false} if the user is authenticated but not
     * permitted
     */
    public boolean isAnonymous() {
        return anonymous;
    }
}
