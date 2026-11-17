package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.Field;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.screens.api.ScreenContentsReader;
import ru.rasulov.screens.api.dto.ScreenContentsResponse;
import ru.rasulov.screens.api.dto.ScreenContentsResponse.*;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

@ExportAsService(ScreenContentsReader.class)
@Named
public class ScreenContentsReaderImpl implements ScreenContentsReader {
    public static final int MAX_SCREEN_ITEMS_PER_PAGE = 200;
    private final AdministratorGuard guard;
    private final FieldScreenManager screens;
    private final FieldManager fields;

    @Inject
    public ScreenContentsReaderImpl(AdministratorGuard guard, @ComponentImport FieldScreenManager screens,
                                    @ComponentImport FieldManager fields) {
        this.guard = guard;
        this.screens = screens;
        this.fields = fields;
    }

    @Override
    public ScreenContentsResponse read(long screenId, String cursor) {
        guard.requireAdministrator();
        if (screenId <= 0) throw new InvalidRequestException("screenId must be a positive integer from get_project_screens.");
        FieldScreen screen = screens.getFieldScreen(screenId);
        if (screen == null) return ScreenContentsResponse.failed(ErrorCode.SCREEN_NOT_FOUND,
                "Screen " + screenId + " was not found. Obtain screenId from get_project_screens; screen names are not unique.");
        ScreenView view = new ScreenView(screen.getId(), screen.getName(), screen.getDescription());
        List<Tab> tabs = new ArrayList<Tab>();
        int totalFields = 0;
        int units = 0;
        // Capture scalars once: hashing and page output must describe the same observation.
        for (FieldScreenTab source : screen.getTabs()) {
            Tab tab = new Tab(source);
            tabs.add(tab);
            totalFields += tab.items.size();
            units += Math.max(1, tab.items.size());
        }
        tabs.sort(Comparator.comparingInt(tab -> tab.position));
        String fingerprint = fingerprint(view, tabs);
        int start = cursor == null ? 0 : start(cursor, screenId, fingerprint, units);
        int end = start + Math.min(MAX_SCREEN_ITEMS_PER_PAGE, units - start);
        int offset = 0;
        List<TabView> page = new ArrayList<TabView>();
        for (Tab tab : tabs) {
            int size = Math.max(1, tab.items.size());
            int from = Math.max(0, start - offset);
            int to = Math.min(size, end - offset);
            if (from < to) {
                List<FieldView> rows = new ArrayList<FieldView>();
                for (int i = from; i < to && i < tab.items.size(); i++) {
                    Item item = tab.items.get(i);
                    boolean custom = fields.isCustomFieldId(item.fieldId);
                    Field field = fields.getField(item.fieldId);
                    Kind kind = field == null ? Kind.UNKNOWN : custom ? Kind.CUSTOM : Kind.SYSTEM;
                    rows.add(new FieldView(item.id, item.fieldId, field == null ? null : field.getName(),
                            kind, item.position, item.shown));
                }
                page.add(new TabView(tab.id, tab.name, tab.position, tab.items.size(), rows));
            }
            offset += size;
            if (offset >= end) break;
        }
        String next = end < units ? next(screenId, fingerprint, end) : null;
        return ScreenContentsResponse.read(view, tabs.size(), totalFields, next, page);
    }

    private static String next(long screenId, String fingerprint, int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("1\n" + screenId + "\n" + fingerprint + "\n" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int start(String cursor, long screenId, String fingerprint, int units) {
        String[] parts;
        int offset;
        try {
            if (cursor.isEmpty() || cursor.length() > 512 || !cursor.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 4 || !"1".equals(parts[0]) || !Long.toString(screenId).equals(parts[1])
                    || !parts[2].matches("[0-9a-f]{64}") || !parts[3].matches("[1-9][0-9]*"))
                throw new IllegalArgumentException();
            offset = Integer.parseInt(parts[3]);
            if (offset % MAX_SCREEN_ITEMS_PER_PAGE != 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
        if (!fingerprint.equals(parts[2])) throw new InvalidRequestException(
                "Screen contents changed. Discard previous pages and restart get_screen_contents without cursor.");
        if (offset >= units) throw invalidCursor();
        return offset;
    }

    private static InvalidRequestException invalidCursor() {
        return new InvalidRequestException("Invalid screen cursor. Please restart without cursor.");
    }

    /** Length-prefixed UTF-8 strings (including null), fixed-width scalars and list counts are unambiguous. */
    private static String fingerprint(ScreenView screen, List<Tab> tabs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DataOutputStream out = new DataOutputStream(new DigestOutputStream(new OutputStream() {
                @Override
                public void write(int value) { }

                @Override
                public void write(byte[] value, int offset, int length) { }
            }, digest));
            out.writeLong(screen.getId());
            text(out, screen.getName());
            text(out, screen.getDescription());
            out.writeInt(tabs.size());
            for (Tab tab : tabs) {
                out.writeLong(tab.id);
                text(out, tab.name);
                out.writeInt(tab.position);
                out.writeInt(tab.items.size());
                for (Item item : tab.items) {
                    out.writeLong(item.id);
                    text(out, item.fieldId);
                    out.writeInt(item.position);
                    out.writeBoolean(item.shown);
                }
            }
            byte[] hash = digest.digest();
            char[] hex = new char[hash.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < hash.length; i++) {
                int value = hash[i] & 255;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Could not fingerprint screen contents", e);
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static final class Tab {
        final Long id;
        final String name;
        final int position;
        final List<Item> items = new ArrayList<Item>();

        Tab(FieldScreenTab tab) {
            id = tab.getId();
            name = tab.getName();
            position = tab.getPosition();
            for (FieldScreenLayoutItem item : tab.getFieldScreenLayoutItems()) items.add(new Item(item));
            items.sort(Comparator.comparingInt(item -> item.position));
        }
    }

    private static final class Item {
        final Long id;
        final String fieldId;
        final int position;
        final boolean shown;

        Item(FieldScreenLayoutItem item) {
            id = item.getId();
            fieldId = item.getFieldId();
            position = item.getPosition();
            shown = item.isShownWhenEmpty();
        }
    }
}
