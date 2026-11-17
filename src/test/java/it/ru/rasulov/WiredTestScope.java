package it.ru.rasulov;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Test-local namespace and explicit ownership; a matching prefix never authorizes cleanup. */
public final class WiredTestScope {
    public static final String ENABLE_PROPERTY = "control.wired.tests.enabled";

    private final String runId;
    private final String keySuffix;
    private final Map<String, Map<String, String>> owned = new LinkedHashMap<String, Map<String, String>>();

    public WiredTestScope() {
        this(UUID.randomUUID());
    }

    WiredTestScope(UUID runId) {
        this.runId = runId.toString().replace("-", "");
        String base36 = new BigInteger(this.runId, 16).toString(36).toUpperCase(Locale.ROOT);
        this.keySuffix = "000000000".substring(Math.min(base36.length(), 9)) + base36;
    }

    /** Must run inside the Jira test JVM before authentication, writes or cleanup. */
    public void requireEnabled() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            throw new IllegalStateException("Destructive wired tests are disabled. Use an isolated disposable Jira "
                    + "and explicitly set -D" + ENABLE_PROPERTY + "=true on its JVM to enable them.");
        }
    }

    public String prefix(String label) {
        return "wired-test-" + label + "-" + runId + "-";
    }

    public String projectKey(String label) {
        if (label == null || !label.matches("[A-Z]{1,3}")) {
            throw new IllegalArgumentException("Project key label must contain one to three uppercase letters.");
        }
        int suffixLength = 10 - label.length();
        return label + keySuffix.substring(keySuffix.length() - suffixLength);
    }

    /** Call only after a creation returns an identity; EXISTS and lookup results are not evidence of creation. */
    public synchronized void record(String kind, String id, String name) {
        if (kind == null || kind.isEmpty() || id == null || id.isEmpty() || name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Ownership requires an object kind, id and name.");
        }
        Map<String, String> identities = owned.get(kind);
        if (identities == null) {
            identities = new LinkedHashMap<String, String>();
            owned.put(kind, identities);
        }
        String previous = identities.get(id);
        if (previous != null && !previous.equals(name)) {
            throw new IllegalStateException("Refusing to replace recorded ownership of " + kind + " " + id);
        }
        identities.put(id, name);
    }

    public synchronized boolean owns(String kind, String id, String name) {
        Map<String, String> identities = owned.get(kind);
        return identities != null && name != null && name.equals(identities.get(id));
    }

    public synchronized Set<String> ids(String kind) {
        Map<String, String> identities = owned.get(kind);
        return identities == null ? Collections.<String>emptySet()
                : new LinkedHashSet<String>(identities.keySet());
    }
}
