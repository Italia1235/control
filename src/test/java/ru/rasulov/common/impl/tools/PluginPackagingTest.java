package ru.rasulov.common.impl.tools;

import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** Run explicitly after package with -Dcontrol.packagedJar=...; never borrows host Jackson. */
public class PluginPackagingTest {
    private Path artifact;

    @Before
    public void requirePackagedArtifact() {
        String path = System.getProperty("control.packagedJar");
        assumeNotNull(path);
        artifact = Paths.get(path).toAbsolutePath();
        assertTrue("Packaged plugin must exist: " + artifact, Files.isRegularFile(artifact));
    }

    @Test
    public void jacksonPackagesDoNotRequireExportsFromJira() throws Exception {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            String imports = jar.getManifest().getMainAttributes().getValue("Import-Package");
            assertNotNull(imports);
            assertFalse("Plugin must not require host Jackson 1: " + imports,
                    imports.contains("org.codehaus.jackson"));
            assertFalse("Plugin must use its bundled Jackson 2, not the host version: " + imports,
                    imports.contains("com.fasterxml.jackson"));
        }
    }


    @Test
    public void packagedMapperReadsAndWritesWorkflowDtoWithoutHostLibraries() throws Exception {
        Path scratch = Files.createTempDirectory(Paths.get("target"), "packaged-jackson-");
        try {
            List<URL> classpath = new ArrayList<URL>();
            classpath.add(artifact.toUri().toURL());
            try (JarFile jar = new JarFile(artifact.toFile())) {
                String bundleClasspath = jar.getManifest().getMainAttributes().getValue("Bundle-ClassPath");
                if (bundleClasspath != null) for (String entry : bundleClasspath.split(",")) {
                    String name = entry.trim();
                    if (".".equals(name)) continue;
                    JarEntry nested = jar.getJarEntry(name);
                    assertNotNull("Missing Bundle-ClassPath entry: " + name, nested);
                    Path file = scratch.resolve("library-" + classpath.size() + ".jar");
                    try (InputStream in = jar.getInputStream(nested)) { Files.copy(in, file); }
                    classpath.add(file.toUri().toURL());
                }
            }
            // Platform/ext parent supplies JDK modules (including java.sql), but not Maven/Jira/test libraries.
            try (URLClassLoader loader = new URLClassLoader(classpath.toArray(new URL[0]),
                    ClassLoader.getSystemClassLoader().getParent())) {
                Class<?> mapperType = loader.loadClass("com.fasterxml.jackson.databind.ObjectMapper");
                Object mapper = mapperType.getConstructor().newInstance();
                Class<?> requestType = loader.loadClass("ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest");
                Object request = mapperType.getMethod("readValue", String.class, Class.class).invoke(mapper,
                        "{\"dryRun\":false,\"planId\":\"approved-plan\"}", requestType);
                assertEquals(Boolean.FALSE, requestType.getMethod("isDryRun").invoke(request));
                assertEquals("approved-plan", requestType.getMethod("getPlanId").invoke(request));
                String json = (String) mapperType.getMethod("writeValueAsString", Object.class).invoke(mapper, request);
                Object roundTrip = mapperType.getMethod("readValue", String.class, Class.class).invoke(mapper, json, requestType);
                assertEquals(Boolean.FALSE, requestType.getMethod("isDryRun").invoke(roundTrip));
                assertEquals("approved-plan", requestType.getMethod("getPlanId").invoke(roundTrip));
                Object preview = mapperType.getMethod("readValue", String.class, Class.class).invoke(mapper,
                        "{\"dryRun\":true,\"existing\":{\"workflow\":\"Delivery\",\"version\":\"DRAFT\","
                                + "\"expectedRevision\":\"revision-1\"},\"transitionRules\":[{\"actionId\":21,"
                                + "\"screen\":\"KEEP\",\"addValidators\":[{\"template\":\"comment_required\"}]}]}", requestType);
                String previewJson = (String) mapperType.getMethod("writeValueAsString", Object.class).invoke(mapper, preview);
                Object previewRoundTrip = mapperType.getMethod("readValue", String.class, Class.class)
                        .invoke(mapper, previewJson, requestType);
                assertEquals(Boolean.TRUE, requestType.getMethod("isDryRun").invoke(previewRoundTrip));
                Object existing = requestType.getMethod("existing").invoke(previewRoundTrip);
                assertEquals("Delivery", existing.getClass().getMethod("workflow").invoke(existing));
                assertEquals("DRAFT", existing.getClass().getMethod("version").invoke(existing));
                assertEquals("revision-1", existing.getClass().getMethod("expectedRevision").invoke(existing));
                List<?> rules = (List<?>) requestType.getMethod("transitionRules").invoke(previewRoundTrip);
                assertEquals(1, rules.size());
                assertEquals(Integer.valueOf(21), rules.get(0).getClass().getMethod("actionId").invoke(rules.get(0)));
            }
        } finally {
            try (Stream<Path> paths = Files.walk(scratch)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
