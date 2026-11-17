package ru.rasulov.common.impl;

import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A {@link ChangeLinks} for tests: Jira lives at {@code http://jira.test/jira}. */
public final class TestLinks {
    public static final String BASE_URL = "http://jira.test/jira";

    private TestLinks() { }

    public static ChangeLinks links() {
        ApplicationProperties properties = mock(ApplicationProperties.class);
        when(properties.getBaseUrl(UrlMode.CANONICAL)).thenReturn(BASE_URL);
        return new ChangeLinks(properties);
    }
}
