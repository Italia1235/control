package ru.rasulov.customfields.api;

import ru.rasulov.customfields.api.dto.screen.AddToScreensRequest;
import ru.rasulov.customfields.api.dto.screen.AddToScreensResponse;
import ru.rasulov.customfields.api.dto.screen.ScreenPlan;
import ru.rasulov.customfields.api.dto.screen.Recommendation;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;

/**
 * Puts a custom field on screens, in two steps with the user in between: {@link #planScreens} reads the screens the
 * field could go to and how many projects each affects, {@link #addToScreens} adds it to the ones the user agreed to.
 * Nothing is added by itself.
 * <p>
 * Both operations require the global {@code ADMINISTER} permission of the current user. Size limits are in
 * {@link Limits}.
 */
public interface FieldScreenService {

    /**
     * Works out the screens of the projects and issue types in the contexts the field really has, for the operations
     * create, edit and view, and how far each screen reaches (projects outside the contexts that share it).
     * Read-only.
     *
     * @param fieldId {@code customfield_10123}
     * @throws InvalidRequestException    there is no such custom field
     * @throws AccessException the current user is anonymous or not a Jira administrator
     */
    ScreenPlan planScreens(String fieldId);

    /** Read a stable continuation; only RECOMMENDED is supported as an optional filter. */
    ScreenPlan planScreens(String fieldId, String cursor, Recommendation recommendation);

    /**
     * Adds every field to every selected screen, on the first tab (or the named one). The default is append;
     * explicit FIRST, LAST, BEFORE and AFTER placement preserves the request's field order among new additions.
     * Idempotent: an existing field is reported as {@code ALREADY_PRESENT}, never moved or used as an insertion cursor.
     * Every field gets a result per screen; one failed screen does not stop the others. With {@code dryRun} nothing
     * is written. Positioned previews also bind the screen layout, including Jira's raw positions, until apply.
     *
     * @throws InvalidRequestException    no fields, an unknown or repeated field (nothing is changed then), no screens,
     *                                    or over a {@link Limits} limit
     * @throws AccessException the current user is anonymous or not a Jira administrator
     * @throws BusyException   another call was changing screens for too long; nothing was changed
     */
    AddToScreensResponse addToScreens(AddToScreensRequest request);
}
