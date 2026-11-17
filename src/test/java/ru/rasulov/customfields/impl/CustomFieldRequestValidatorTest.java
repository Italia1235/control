package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.customfields.impl.validation.FieldFailureException;

import java.util.List;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.SYSTEM;
import static ru.rasulov.customfields.impl.JiraMocks.TEXTAREA;
import static ru.rasulov.customfields.impl.JiraMocks.TEXTFIELD;
import static ru.rasulov.customfields.impl.JiraMocks.TEXT_SEARCHER;
import static ru.rasulov.customfields.impl.JiraMocks.searcher;
import static ru.rasulov.customfields.impl.JiraMocks.type;

@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomFieldRequestValidatorTest {

    private CustomFieldManager manager;
    private CustomFieldType<?, ?> textfield;
    private CustomFieldType<?, ?> textarea;
    private CustomFieldSearcher textSearcher;
    private CustomFieldRequestValidator validator;

    @Before
    public void setUp() {
        manager = mock(CustomFieldManager.class);
        textfield = type(CustomFieldType.class, TEXTFIELD, "Text Field (single line)");
        textarea = type(CustomFieldType.class, TEXTAREA, "Text Field (multi-line)");
        textSearcher = searcher(TEXT_SEARCHER, TEXTFIELD, TEXTAREA);
        when(manager.getCustomFieldType(TEXTFIELD)).thenReturn((CustomFieldType) textfield);
        when(manager.getCustomFieldType(TEXTAREA)).thenReturn((CustomFieldType) textarea);
        when(manager.getCustomFieldTypes()).thenReturn(Arrays.<CustomFieldType<?, ?>>asList(textfield, textarea));
        when(manager.getDefaultSearcher(textfield)).thenReturn(textSearcher);
        when(manager.getCustomFieldSearchers(textfield)).thenReturn(Collections.singletonList(textSearcher));
        validator = JiraMocks.validator(manager);
    }

    @Test
    public void shortTypeKeyIsExpandedToSystemKey() {
        assertSame(textarea, validator.validate(def("Notes", "textarea", null)).getType());
    }

    @Test
    public void fullTypeKeyIsUsedAsIs() {
        assertSame(textfield, validator.validate(def("Notes", TEXTFIELD, null)).getType());
    }

    @Test
    public void nameIsTrimmedAndDescriptionDefaultsToEmpty() {
        ResolvedFieldDefinition resolved = validator.validate(def("  Notes  ", "textfield", null));
        assertEquals("Notes", resolved.getName());
        assertEquals("", resolved.getDescription());
    }

    @Test
    public void defaultSearcherIsUsedWhenSearcherKeyOmitted() {
        assertSame(textSearcher, validator.validate(def("Notes", "textfield", null)).getSearcher());
    }

    @Test
    public void explicitCompatibleSearcherIsAccepted() {
        when(manager.getCustomFieldSearcher(TEXT_SEARCHER)).thenReturn(textSearcher);
        assertSame(textSearcher, validator.validate(def("Notes", "textfield", TEXT_SEARCHER)).getSearcher());
    }

    @Test
    public void unknownTypeSuggestsResemblingInstalledTypes() {
        FieldFailureException failure = expectFailure(def("Notes", "textfeld", null));
        assertEquals(CustomFieldErrorCode.UNKNOWN_TYPE, failure.getErrorCode());
        assertEquals(Arrays.asList(TEXTAREA, TEXTFIELD), failure.getSuggestions());
        assertTrue(failure.getMessage(), failure.getMessage().contains("list_custom_field_types"));
    }

    @Test
    public void unknownTypeSuggestionsAreCappedAtFive() {
        CustomFieldType<?, ?>[] many = new CustomFieldType<?, ?>[8];
        for (int i = 0; i < many.length; i++) {
            many[i] = type(CustomFieldType.class, "p:textual" + i, "Textual " + i);
        }
        when(manager.getCustomFieldTypes()).thenReturn(Arrays.<CustomFieldType<?, ?>>asList(many));
        assertEquals(5, expectFailure(def("Notes", "textual", null)).getSuggestions().size());
    }

    @Test
    public void incompatibleSearcherListsCompatibleOnes() {
        String numberSearcherKey = SYSTEM + "exactnumber";
        CustomFieldSearcher numberSearcher = searcher(numberSearcherKey, SYSTEM + "float");
        when(manager.getCustomFieldSearcher(numberSearcherKey)).thenReturn(numberSearcher);
        FieldFailureException failure = expectFailure(def("Notes", "textfield", numberSearcherKey));
        assertEquals(CustomFieldErrorCode.INCOMPATIBLE_SEARCHER, failure.getErrorCode());
        assertEquals(Collections.singletonList(TEXT_SEARCHER), failure.getSuggestions());
    }

    @Test
    public void unknownSearcherIsIncompatibleSearcher() {
        FieldFailureException failure = expectFailure(def("Notes", "textfield", "no.such:searcher"));
        assertEquals(CustomFieldErrorCode.INCOMPATIBLE_SEARCHER, failure.getErrorCode());
        assertEquals(Collections.singletonList(TEXT_SEARCHER), failure.getSuggestions());
    }

    @Test
    public void selectKeepsOptionsInRequestOrderTrimmed() {
        optionType("select");

        ResolvedFieldDefinition resolved = validator.validate(withOptions("select", opt(" High "), opt("Low")));

        assertEquals("High", options(resolved).get(0).getValue());
        assertEquals("Low", options(resolved).get(1).getValue());
    }

    @Test
    public void selectWithoutOptionsIsAccepted() {
        optionType("select");

        assertTrue(options(validator.validate(def("Severity", "select", null))).isEmpty());
    }

    @Test
    public void optionsOnATypeWithoutOptionsAreRejectedAndTypesWithOptionsSuggested() {
        CustomFieldType<?, ?> select = optionType("select");
        when(manager.getCustomFieldTypes()).thenReturn(Arrays.<CustomFieldType<?, ?>>asList(textfield, select));

        FieldFailureException failure = expectFailure(withOptions("textfield", opt("a")));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        assertEquals(Collections.singletonList(SYSTEM + "select"), failure.getSuggestions());
    }

    @Test
    public void blankOrMissingOptionValueIsRejected() {
        optionType("select");

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, expectFailure(withOptions("select", opt("a"), opt("  "))).getErrorCode());
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, expectFailure(withOptions("select", opt(null))).getErrorCode());
    }

    @Test
    public void optionLongerThan255IsRejectedAndExactly255IsAccepted() {
        optionType("select");
        char[] chars = new char[256];
        Arrays.fill(chars, 'a');

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR,
                expectFailure(withOptions("select", opt(new String(chars)))).getErrorCode());
        validator.validate(withOptions("select", opt(new String(chars, 0, 255))));
    }

    @Test
    public void duplicateOptionsAreRejectedIgnoringCaseAndSpaces() {
        optionType("select");

        FieldFailureException failure = expectFailure(withOptions("select", opt("Low"), opt("High"), opt(" low ")));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("'low'"));
    }

    @Test
    public void childrenAreRejectedForANonCascadingType() {
        optionType("select");

        FieldFailureException failure = expectFailure(withOptions("select", opt("EU", opt("Berlin"))));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        assertEquals(Collections.singletonList(SYSTEM + "cascadingselect"), failure.getSuggestions());
    }

    @Test
    public void cascadingAcceptsChildrenAndTheSameChildUnderDifferentParents() {
        optionType("cascadingselect");

        ResolvedFieldDefinition resolved = validator.validate(withOptions("cascadingselect",
                opt("EU", opt("Berlin"), opt(" Paris ")), opt("US", opt("Berlin"))));

        assertEquals("Paris", options(resolved).get(0).getChildren().get(1).getValue());
        assertEquals("Berlin", options(resolved).get(1).getChildren().get(0).getValue());
    }

    @Test
    public void cascadingRejectsDuplicateChildrenUnderOneParent() {
        optionType("cascadingselect");

        FieldFailureException failure = expectFailure(withOptions("cascadingselect",
                opt("EU", opt("Berlin"), opt("berlin"))));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("under 'EU'"));
    }

    @Test
    public void exactlyTheMaximumNumberOfRootOptionsIsAcceptedAndOneMoreIsRejected() {
        optionType("select");

        ResolvedFieldDefinition resolved = validator.validate(withOptions("select", numbered(Limits.MAX_ROOT_OPTIONS)));
        FieldFailureException failure = expectFailure(withOptions("select", numbered(Limits.MAX_ROOT_OPTIONS + 1)));

        assertEquals(Limits.MAX_ROOT_OPTIONS, options(resolved).size());
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
    }

    @Test
    public void exactlyTheMaximumNumberOfChildrenIsAcceptedAndOneMoreIsRejectedNamingTheParent() {
        optionType("cascadingselect");

        ResolvedFieldDefinition resolved = validator.validate(withOptions("cascadingselect",
                parent("EU", Limits.MAX_CHILD_OPTIONS_PER_PARENT)));
        FieldFailureException failure = expectFailure(withOptions("cascadingselect",
                parent("EU", Limits.MAX_CHILD_OPTIONS_PER_PARENT + 1)));

        assertEquals(Limits.MAX_CHILD_OPTIONS_PER_PARENT, options(resolved).get(0).getChildren().size());
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("'EU'"));
    }

    @Test
    public void theChildLimitIsPerParentNotForTheWholeTree() {
        optionType("cascadingselect");

        ResolvedFieldDefinition resolved = validator.validate(withOptions("cascadingselect",
                parent("EU", Limits.MAX_CHILD_OPTIONS_PER_PARENT), parent("US", Limits.MAX_CHILD_OPTIONS_PER_PARENT)));

        assertEquals(2, options(resolved).size());
    }

    @Test
    public void totalOptionsOfOneFieldAreCappedIncludingChildren() {
        optionType("cascadingselect");
        // 5 parents + 5 x 99 children = exactly the maximum
        OptionDefinition[] atLimit = new OptionDefinition[5];
        for (int i = 0; i < 5; i++) {
            atLimit[i] = parent("parent " + i, 99);
        }
        OptionDefinition[] overLimit = atLimit.clone();
        overLimit[0] = parent("parent 0", 100);

        assertEquals(5, options(validator.validate(withOptions("cascadingselect", atLimit))).size());
        assertEquals(Limits.MAX_OPTIONS_PER_CONTEXT, 5 + 5 * 99);
        FieldFailureException failure = expectFailure(withOptions("cascadingselect", overLimit));
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
    }

    private static OptionDefinition parent(String value, int children) {
        return new OptionDefinition(value, Arrays.asList(numbered(children)));
    }

    private static OptionDefinition[] numbered(int count) {
        OptionDefinition[] options = new OptionDefinition[count];
        for (int i = 0; i < count; i++) {
            options[i] = new OptionDefinition("value " + i, null);
        }
        return options;
    }

    @Test
    public void cascadingRejectsAThirdLevel() {
        optionType("cascadingselect");

        FieldFailureException failure = expectFailure(withOptions("cascadingselect",
                opt("EU", opt("Germany", opt("Berlin")))));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("two levels"));
    }

    @Test
    public void blankNameIsRejected() {
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, expectFailure(def("   ", "textfield", null)).getErrorCode());
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, expectFailure(def(null, "textfield", null)).getErrorCode());
    }

    @Test
    public void nameLongerThan255IsRejectedAndExactly255IsAccepted() {
        char[] chars = new char[256];
        Arrays.fill(chars, 'a');
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR,
                expectFailure(def(new String(chars), "textfield", null)).getErrorCode());
        validator.validate(def(new String(chars, 0, 255), "textfield", null));
    }

    @Test
    public void missingTypeKeyIsRejected() {
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, expectFailure(def("Notes", null, null)).getErrorCode());
    }

    @Test
    public void nullDefinitionIsRejected() {
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, expectFailure(null).getErrorCode());
    }

    private static FieldDefinition def(String name, String typeKey, String searcherKey) {
        return new FieldDefinition(name, null, typeKey, searcherKey, null, JiraMocks.GLOBAL, null);
    }

    /** The options that would be written: all tests here use one context. */
    private static List<OptionDefinition> options(ResolvedFieldDefinition resolved) {
        return resolved.getContexts().get(0).getOptions();
    }

    private CustomFieldType<?, ?> optionType(String module) {
        String key = SYSTEM + module;
        CustomFieldType type = type(MultipleSettableCustomFieldType.class, key, module);
        when(manager.getCustomFieldType(key)).thenReturn(type);
        return type;
    }

    private static OptionDefinition opt(String value, OptionDefinition... children) {
        return new OptionDefinition(value, Arrays.asList(children));
    }

    private static FieldDefinition withOptions(String typeKey, OptionDefinition... options) {
        return new FieldDefinition("Field", null, typeKey, null, Arrays.asList(options), JiraMocks.GLOBAL, null);
    }

    private FieldFailureException expectFailure(FieldDefinition definition) {
        try {
            validator.validate(definition);
        } catch (FieldFailureException e) {
            return e;
        }
        fail("expected FieldFailureException");
        return null;
    }
}
