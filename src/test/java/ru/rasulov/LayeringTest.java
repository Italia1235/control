package ru.rasulov;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Keeps the feature areas apart: {@code ru.rasulov.common} knows no feature, and a feature (customfields, workflow, ...)
 * knows {@code common} and itself, never another feature. Otherwise a second feature would silently start to depend on
 * the first, and the two could no longer change independently.
 */
public class LayeringTest {

    private static final String COMMON = "common";
    private static final Pattern AREA_REFERENCE = Pattern.compile("\\bru\\.rasulov\\.(\\w+)\\.");

    @Test
    public void featuresDependOnCommonAndThemselvesOnly() throws IOException {
        File root = new File("src/main/java/ru/rasulov");
        assertTrue("run from the project root, cannot find " + root.getAbsolutePath(), root.isDirectory());
        List<String> areas = new ArrayList<String>();
        for (File area : root.listFiles(File::isDirectory)) {
            areas.add(area.getName());
        }
        assertTrue("the common area is missing", areas.contains(COMMON));
        assertTrue("there is no feature besides common", areas.size() > 1);

        List<String> violations = new ArrayList<String>();
        for (String area : areas) {
            for (File source : javaFiles(new File(root, area))) {
                String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
                for (String other : referencedAreas(text)) {
                    if (areas.contains(other) && !allowed(area, other)) {
                        violations.add(source.getPath().replace('\\', '/') + " uses ru.rasulov." + other);
                    }
                }
            }
        }
        assertEquals("areas must not depend on each other: " + violations, 0, violations.size());
    }

    @Test
    public void theRuleFlagsWhatItShould() {
        assertTrue(allowed("customfields", "common"));
        assertTrue(allowed("customfields", "customfields"));
        assertTrue(allowed("common", "common"));
        assertTrue(!allowed("common", "customfields"));
        assertTrue(!allowed("customfields", "workflow"));
        assertEquals(Arrays.asList("workflow"), referencedAreas(
                "import ru.rasulov.workflow.api.X;\nclass A { ru.rasulov.workflow.api.X x; }"));
    }

    private static boolean allowed(String area, String referenced) {
        return referenced.equals(area) || (!area.equals(COMMON) && referenced.equals(COMMON));
    }

    /** Areas named after {@code ru.rasulov.}, once each, in code or imports alike. */
    private static List<String> referencedAreas(String text) {
        List<String> found = new ArrayList<String>();
        Matcher matcher = AREA_REFERENCE.matcher(text);
        while (matcher.find()) {
            if (!found.contains(matcher.group(1))) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    private static List<File> javaFiles(File dir) {
        List<File> files = new ArrayList<File>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    files.addAll(javaFiles(child));
                } else if (child.getName().endsWith(".java")) {
                    files.add(child);
                }
            }
        }
        return files;
    }
}
