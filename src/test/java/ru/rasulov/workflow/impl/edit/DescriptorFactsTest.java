package ru.rasulov.workflow.impl.edit;

import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Test;
import java.io.PrintWriter;
import static org.junit.Assert.*;

public class DescriptorFactsTest {
    private static String definition(String xml) {
        return DescriptorFacts.definition(new WorkflowDescriptor() {
            @Override public void writeXML(PrintWriter writer,int indent) { writer.print(xml); }
        });
    }
    @Test public void ignoresCompleteNativeStampLinesIncludingTheirIndentationAndLineEnding() {
        for(String newline:new String[]{"\n","\r\n"}) {
            String prefix="<workflow>"+newline, suffix="  <meta name=\"custom\">keep</meta>"+newline+"</workflow>";
            String stamps="  <meta name=\"jira.updated.date\">123</meta>  "+newline
                    +"\t<meta name=\"jira.update.author.key\">admin</meta>"+newline
                    +"  <meta name=\"jira.update.author.name\">Admin</meta>"+newline;
            assertEquals(prefix+suffix,definition(prefix+stamps+suffix));
        }
    }
    @Test public void inlineStampsNeverRemoveTheirSubstantiveNeighborsOrWhitespace() {
        String before="<workflow><meta name=\"custom\">  keep  </meta>",after="<steps/></workflow>";
        assertEquals(before+after,definition(before+"<meta name=\"jira.updated.date\">123</meta>"+after));
        assertEquals("  <steps/>\n<workflow/>",definition("  <steps/><meta name=\"jira.update.author.key\">admin</meta>\n<workflow/>"));
        assertEquals("<workflow>  \n<steps/></workflow>",definition("<workflow>  \n<meta name=\"jira.updated.date\">123</meta><steps/></workflow>"));
    }
    @Test public void preservesEveryOtherMetadataValueIncludingLookalikeNames() {
        String before="<workflow>\n  <meta name=\"jira.updated.date.extra\">123</meta>\n"
                +"  <meta name=\"jira.description\">  description  </meta>\n</workflow>";
        assertEquals(before,definition(before));
        assertFalse(definition(before).equals(definition(before.replace("123","124"))));
        assertFalse(definition(before).equals(definition(before.replace("  description  ","description"))));
    }
}
