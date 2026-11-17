package ru.rasulov.customfields.api;

import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredRequest;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse;

/** Changes fields' required flags in the effective field configurations of one project. */
public interface FieldRequirementService {
    /** Previews with full details, then applies only the preview's planId. */
    SetFieldRequiredResponse setRequired(SetFieldRequiredRequest request);
}
