package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.ValidatorDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.edit.EditErrorCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.edit.ValidatorFixtures.*;

/**
 * The descriptor side of the ScriptRunner "Comment required" validator on the XML sample of ScriptRunner 9.46.0: what is
 * recognized, what is refused, what is written and what is left alone.
 */
public class CommentRequiredDescriptorTest {

    private static final String NEW_ID = "5b0e0f84-6f7e-4a5e-9c55-5e9d8f1a2b3c";

    private static List<RequiredFieldsDescriptor.Entry> scan(WorkflowDescriptor d, int actionId) {
        return RequiredFieldsDescriptor.scan(action(d, actionId));
    }

    // ---- reading -----------------------------------------------------------------------------------------

    @Test
    public void theSampleIsRecognizedAtItsPositionNextToTheRequiredFieldsValidator() {
        List<RequiredFieldsDescriptor.Entry> entries = scan(commentWorkflow(), 21);

        assertEquals(2, entries.size());
        assertTrue(entries.get(0).requiredFields());
        assertFalse(entries.get(0).commentRequired());
        assertFalse(entries.get(1).requiredFields());
        assertTrue(entries.get(1).commentRequired());
        assertEquals(2, entries.get(1).position());
        assertTrue(entries.get(1).fieldIds().isEmpty());
        assertFalse(entries.get(1).conditional());
    }

    @Test
    public void theSampleWithTheFortyHexFunctionIdOfTheStandIsRecognized() {
        assertTrue(scan(commentWorkflow(), 13).get(1).commentRequired());
        assertTrue(CommentRequiredDescriptor.captured(validator(commentRequired(SAMPLE_COMMENT_ID))));
        assertTrue(CommentRequiredDescriptor.captured(validator(commentRequired(NEW_ID))));
    }

    @Test
    public void theCommentScriptInAnyOtherFormIsNotRecognized() {
        assertFalse(scan(commentWorkflow(), 19).get(0).commentRequired());
        assertFalse(scan(commentWorkflow(), 20).get(0).commentRequired());
        assertFalse("the neighbour with a NOTE argument and no function id", scan(workflow(), 21).get(2).commentRequired());
        assertFalse("an empty function id", CommentRequiredDescriptor.captured(validator(commentRequired(""))));
        assertFalse(CommentRequiredDescriptor.captured(validator(commentRequired(NEW_ID).replace("type=\"class\"", "type=\"beanshell\""))));
        assertFalse(CommentRequiredDescriptor.captured(validator(commentRequired("").replace("<arg name=\"FIELD_FUNCTION_ID\"></arg>", ""))));
        assertFalse(CommentRequiredDescriptor.captured(validator(commentRequired(NEW_ID)
                .replace("</validator>", "<arg name=\"FIELD_REQUIRED_FIELDS\">assignee</arg></validator>"))));
        assertFalse(CommentRequiredDescriptor.captured(validator(commentRequired(NEW_ID)
                .replace(COMMENT_CANNED, RequiredFieldsDescriptor.CANNED_SCRIPT))));
        assertFalse(CommentRequiredDescriptor.captured(validator(commentRequired(NEW_ID)
                .replace(RequiredFieldsDescriptor.CLASS_NAME, "com.example.Other"))));
    }

    @Test
    public void everyOtherValidatorIsNotTheCommentValidator() {
        for (RequiredFieldsDescriptor.Entry entry : scan(workflow(), 21)) {
            if (entry.requiredFields()) assertFalse(entry.commentRequired());
        }
        assertFalse(scan(commentWorkflow(), 11).get(0).commentRequired());
        assertFalse(scan(commentWorkflow(), 11).get(1).commentRequired());
    }

    // ---- the screen --------------------------------------------------------------------------------

    /** A transition of the fixture with no screen, given the meta and the view the case needs. */
    private static boolean usable(String meta, String view, FieldScreenManager screens) {
        ActionDescriptor action = action(commentWorkflow(), 14);
        if (meta != null) DescriptorFacts.meta(action).put(DescriptorFacts.SCREEN_META, meta);
        action.setView(view);
        return CommentRequiredDescriptor.screenUsable(action, screens);
    }

    @Test
    public void aScreenIsUsableOnlyWhenTheMetaIsANumberOfAScreenThatExistsAndTheActionHasTheFieldscreenView() {
        FieldScreenManager screens = mock(FieldScreenManager.class);
        when(screens.getFieldScreen(9L)).thenReturn(mock(FieldScreen.class));

        assertTrue(usable("9", "fieldscreen", screens));
        assertTrue(usable(" 9 ", "fieldscreen", screens));
        assertFalse("no meta", usable(null, "fieldscreen", screens));
        assertFalse("empty meta", usable("", "fieldscreen", screens));
        assertFalse("not a number", usable("abc", "fieldscreen", screens));
        assertFalse("a screen Jira does not have", usable("99", "fieldscreen", screens));
    }

    @Test
    public void aMetaWithoutTheFieldscreenViewIsNotAUsableScreen() {
        FieldScreenManager screens = mock(FieldScreenManager.class);
        when(screens.getFieldScreen(9L)).thenReturn(mock(FieldScreen.class));

        assertFalse("no view", usable("9", null, screens));
        assertFalse("another view", usable("9", "resolveissue", screens));
        assertFalse("a view in another case", usable("9", "FieldScreen", screens));
        assertFalse("a view without any meta", usable(null, "fieldscreen", screens));
    }

    @Test
    public void theFixtureTransitionsAreUsableOrNotAsTheirScreensAre() {
        FieldScreenManager screens = mock(FieldScreenManager.class);
        when(screens.getFieldScreen(9L)).thenReturn(mock(FieldScreen.class));
        WorkflowDescriptor d = commentWorkflow();

        assertTrue(CommentRequiredDescriptor.screenUsable(action(d, 12), screens));
        assertTrue(CommentRequiredDescriptor.screenUsable(action(d, 21), screens));
        for (int actionId : new int[] {13, 14, 15, 16, 17, 18}) {
            assertFalse("action " + actionId, CommentRequiredDescriptor.screenUsable(action(d, actionId), screens));
        }
        action(d, 12).setView(null);
        assertFalse(CommentRequiredDescriptor.screenUsable(action(d, 12), screens));
    }

    // ---- planning ----------------------------------------------------------------------------------

    @Test
    public void thePresenceOfTheCapturedValidatorIsReportedAndItsAbsenceToo() {
        WorkflowDescriptor d = commentWorkflow();

        assertTrue(CommentRequiredDescriptor.present(action(d, 12)));
        assertTrue(CommentRequiredDescriptor.present(action(d, 21)));
        assertFalse(CommentRequiredDescriptor.present(action(d, 11)));
        assertFalse(CommentRequiredDescriptor.present(action(d, 14)));
    }

    @Test
    public void theCommentScriptInAnotherFormStopsTheChange() {
        for (int actionId : new int[] {19, 20}) {
            try {
                CommentRequiredDescriptor.present(action(commentWorkflow(), actionId));
                fail("action " + actionId + " must stop the change");
            } catch (EditFailure e) {
                assertEquals(EditErrorCode.UNSUPPORTED_DESCRIPTOR, e.getCode());
            }
        }
    }

    // ---- writing -----------------------------------------------------------------------------------

    @Test
    public void aNewValidatorIsAddedAtTheEndWithTheThreeArgumentsOfTheSampleInItsOrder() {
        WorkflowDescriptor d = commentWorkflow();
        ActionDescriptor action = action(d, 11);
        List<String> before = xml(action);

        CommentRequiredDescriptor.apply(action, NEW_ID);

        List<ValidatorDescriptor> validators = validators(action);
        assertEquals(3, validators.size());
        assertEquals(before, xml(action).subList(0, 2));
        ValidatorDescriptor created = validators.get(2);
        assertEquals("class", created.getType());
        assertEquals("", created.getName());
        assertEquals(Arrays.asList("FIELD_FUNCTION_ID", "canned-script", "class.name"),
                new ArrayList<Object>(created.getArgs().keySet()));
        assertEquals(NEW_ID, created.getArgs().get("FIELD_FUNCTION_ID"));
        assertEquals(COMMENT_CANNED, created.getArgs().get("canned-script"));
        assertEquals(RequiredFieldsDescriptor.CLASS_NAME, created.getArgs().get("class.name"));
        assertTrue(CommentRequiredDescriptor.captured(created));
    }

    @Test
    public void theAddedValidatorIsTheSampleByteForByteAndSurvivesJirasSaveAndReload() {
        WorkflowDescriptor d = commentWorkflow();
        CommentRequiredDescriptor.apply(action(d, 14), NEW_ID);

        assertEquals(DescriptorFacts.xml(validator(commentRequired(NEW_ID))),
                DescriptorFacts.xml(validators(action(d, 14)).get(1)));
        assertEquals(DescriptorFacts.definition(reloaded(d)), DescriptorFacts.definition(d));
        assertTrue(CommentRequiredDescriptor.present(action(reloaded(d), 14)));
    }

    @Test
    public void everyOtherPartOfTheWorkflowIsLeftExactlyAsItWas() {
        WorkflowDescriptor d = commentWorkflow();
        String conditions = DescriptorFacts.xml(action(d, 11).getRestriction());
        String results = DescriptorFacts.xml(action(d, 11).getUnconditionalResult());
        String meta = DescriptorFacts.meta(action(d, 11)).toString();
        String initial = DescriptorFacts.xml(action(d, 1));
        List<String> neighbours = new ArrayList<String>();
        for (DescriptorFacts.Located l : DescriptorFacts.locate(d).values()) {
            if (l.id() != 11) neighbours.add(DescriptorFacts.xml(l.action()));
        }

        CommentRequiredDescriptor.apply(action(d, 11), NEW_ID);

        assertEquals(conditions, DescriptorFacts.xml(action(d, 11).getRestriction()));
        assertEquals(results, DescriptorFacts.xml(action(d, 11).getUnconditionalResult()));
        assertEquals(meta, DescriptorFacts.meta(action(d, 11)).toString());
        assertEquals(initial, DescriptorFacts.xml(action(d, 1)));
        List<String> after = new ArrayList<String>();
        for (DescriptorFacts.Located l : DescriptorFacts.locate(d).values()) {
            if (l.id() != 11) after.add(DescriptorFacts.xml(l.action()));
        }
        assertEquals(neighbours, after);
    }

    private static List<String> xml(ActionDescriptor action) {
        List<String> xml = new ArrayList<String>();
        for (ValidatorDescriptor v : validators(action)) xml.add(DescriptorFacts.xml(v));
        return xml;
    }
}
