package ru.rasulov.common.impl.tools;

import com.atlassian.plugin.PluginAccessor;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * omp 18.8.0 hands a connected server's {@code instructions} to the model cut at 4,000 characters
 * ({@code slice(0, 4000)} + "[truncated]", read from the executable). The rules below must be inside that head, whatever
 * is added to the long sections later; a client that delivers more does not make them less necessary.
 */
public class InstructionsDeliveryTest {
    private static final int CLIENT_LIMIT = 4000;

    @Test
    public void theRulesThatMustNeverBeLostAreInsideTheFirst4000Characters() {
        String head = new ToolCatalog(mock(PluginAccessor.class)).instructions().substring(0, CLIENT_LIMIT);
        String[] mustSurvive = {
                "planId is not approval",          // a yes to the shown preview is the only approval
                "start_operation",                  // the only way to write
                "PREVIEW_STALE",                    // stale: new preview, new yes
                "QUEUED/RUNNING are not completion",
                "alreadyStarted",                   // lost response: the same planId, no second create
                "never create again",
                "UNKNOWN are not success",
                "never say nothing changed",
                "never repeat the plan",
                "publishing a draft",               // global effects named before the yes
                "never guess them",                 // mappings of occupied statuses and types
                "Never make a field required",
        };
        for (String rule : mustSurvive) {
            assertTrue("missing from the first " + CLIENT_LIMIT + " characters: " + rule, head.contains(rule));
        }
    }
}
