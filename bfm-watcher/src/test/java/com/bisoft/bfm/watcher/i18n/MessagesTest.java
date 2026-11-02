package com.bisoft.bfm.watcher.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's languages: complete bundles, keys the code uses, placeholders, the runtime catalog. The console ships
 * English; the multi-language parts are checked with sample data, so they keep working for a language added later.
 */
class MessagesTest {

    private static final Pattern NUMBERED = Pattern.compile("\\{\\d+}");
    private static final Pattern NAMED = Pattern.compile("\\{[a-zA-Z_][a-zA-Z0-9_]*}");
    private static final Path UI_SOURCES = Path.of("src/main/java/com/bisoft/bfm/watcher");

    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    private static final Locale ARABIC = Locale.forLanguageTag("ar");

    private final Messages messages = new Messages();

    @Test
    void everyLanguageHasEveryKeyWithTheSamePlaceholders() {
        Set<String> en = messages.keys(Messages.ENGLISH);
        assertTrue(en.size() > 400, "the English bundle has the console's texts: " + en.size());
        for (Locale l : Messages.LOCALES) {
            Set<String> keys = messages.keys(l);
            Set<String> missing = new TreeSet<>(en);
            missing.removeAll(keys);
            Set<String> extra = new TreeSet<>(keys);
            extra.removeAll(en);
            assertEquals(Set.of(), missing, l + ": keys without a translation");
            assertEquals(Set.of(), extra, l + ": keys English does not have");
            for (String k : en) {
                assertEquals(placeholders(NUMBERED, messages.getTranslation(k, Messages.ENGLISH)),
                        placeholders(NUMBERED, messages.getTranslation(k, l)), l + " " + k + ": placeholders");
                assertFalse(messages.getTranslation(k, l).isBlank(), l + " " + k + ": empty");
            }
        }
    }

    @Test
    void everyKeyTheCodeAsksForExists() throws IOException {
        Set<String> en = messages.keys(Messages.ENGLISH);
        Set<String> used = new TreeSet<>();
        Pattern call = Pattern.compile("Ui\\.t\\(\"([a-zA-Z0-9_.]+)\"");
        Pattern ternary = Pattern.compile("Ui\\.t\\([^\"]*\\?\\s*\"([a-zA-Z0-9_.]+)\"\\s*:\\s*\"([a-zA-Z0-9_.]+)\"");
        Pattern titles = Pattern.compile("super\\(hub, \"([a-zA-Z0-9_.]+)\"\\)");
        try (Stream<Path> files = Files.walk(UI_SOURCES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f);
                for (Pattern p : new Pattern[] {call, titles}) {
                    Matcher m = p.matcher(text);
                    while (m.find()) {
                        used.add(m.group(1));
                    }
                }
                Matcher m = ternary.matcher(text);
                while (m.find()) {
                    used.add(m.group(1));
                    used.add(m.group(2));
                }
            }
        }
        // keys the code builds from a prefix
        for (String page : new String[] {"overview", "replication", "switchover", "operations", "events", "notifications"}) {
            used.add("nav." + page);
        }
        for (String s : new String[] {"basics", "nodes", "enroll", "checks", "start", "review"}) {
            used.add("ac.step." + s);
        }
        for (String s : new String[] {"running", "succeeded", "failed"}) {
            used.add("op." + s);
        }
        for (String s : new String[] {"all", "critical", "warning", "info"}) {
            used.add("sev." + s);
        }
        for (Locale l : Messages.LOCALES) {
            used.add("lang." + l.getLanguage());
        }
        // the patterns also catch enum names compared in a ternary ("FAILOVER") and the prefixes of built keys ("nav.")
        used.removeIf(k -> !k.contains(".") || k.endsWith(".") || !Character.isLowerCase(k.charAt(0)));
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(en);
        assertEquals(Set.of(), missing, "keys used in the code but not in messages.properties");
        assertTrue(used.size() > 400, "the scan found the console's keys: " + used.size());
    }

    @Test
    void argumentsAreFilledLiterallyAndMissingKeysFallBack() {
        assertEquals("Watchers 2/3 · leader w1", messages.getTranslation("header.watchers", Messages.ENGLISH, 2, 3, "w1"));
        // an apostrophe needs no escaping (no MessageFormat), numbers keep Latin digits whatever the language
        assertEquals("Watcher'lar 2/3 · lider w1", Messages.fill("Watcher'lar {0}/{1} · lider {2}", 2, 3, "w1"));
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"));
            assertTrue(messages.getTranslation("ov.leaseLeft", ARABIC, 12).contains("12"));
        } finally {
            Locale.setDefault(before);
        }
        assertEquals("no.such.key", messages.getTranslation("no.such.key", TURKISH));
        // a language without a bundle gets English
        assertEquals("Watchers 2/3 · leader w1", messages.getTranslation("header.watchers", TURKISH, 2, 3, "w1"));
        // arguments go in once: a name that contains "{1}" is shown as it is
        assertEquals("Watchers 2/{1} · leader w1", messages.getTranslation("header.watchers", Messages.ENGLISH, 2,
                "{1}", "w1"));
        assertEquals(Messages.ENGLISH, Messages.LOCALES.get(0), "English first");
        assertEquals(Messages.LOCALES.size() > 1, Messages.severalLanguages());
        for (Locale l : Messages.LOCALES) {
            assertEquals(l, Messages.supported(Locale.forLanguageTag(l.getLanguage() + "-ZZ")), "region ignored");
        }
        assertEquals(Messages.ENGLISH, Messages.supported(Locale.GERMAN));
        assertTrue(Messages.rightToLeft(ARABIC));
        assertTrue(Messages.rightToLeft(Locale.forLanguageTag("he")));
        assertFalse(Messages.rightToLeft(TURKISH));
        assertFalse(Messages.rightToLeft(Messages.ENGLISH));
    }

    @Test
    void theLanguageListIsReadFromItsResource() {
        // sample list (test resource): comments, blank lines, regions and repeats; English always first
        assertEquals(List.of(Messages.ENGLISH, TURKISH, Locale.forLanguageTag("ar-SA")),
                Messages.languages("i18n/sample-languages"));
        assertEquals(List.of(Messages.ENGLISH), Messages.languages("i18n/no-such-file"));
    }

    @Test
    void runtimeTextsAreTranslatedByTemplateAndUnknownOnesStayEnglish() {
        // English is never touched; a text the catalog does not know comes back as it is
        assertEquals("Healthy", messages.runtime("Healthy", Messages.ENGLISH));
        assertEquals("some agent said something", messages.runtime("some agent said something", TURKISH));
        assertEquals(null, messages.runtime(null, ARABIC));
        for (Locale l : Messages.LOCALES) {
            for (String en : messages.runtimeTemplates(l)) {
                assertEquals(placeholders(NAMED, en), placeholders(NAMED, messages.runtime(en, l)), l + ": " + en);
            }
        }
        // the catalog itself, with sample entries: events as the watcher writes them, the template's values are
        // translated too, names stay as they are
        Messages.Catalog c = new Messages.Catalog(Map.of(
                "Automatic failover {oldPrimary} -> {target} completed",
                "Otomatik failover {oldPrimary} -> {target} tamamlandı",
                "Automatic failover not started: {reason}", "Otomatik failover başlatılmadı: {reason}",
                "maintenance mode is on", "bakım modu açık",
                "the VIP is on {primary} now, as expected; it moves to {target} after {primary2} stops",
                "VIP beklendiği gibi şu an {primary} üzerinde; {primary2} durduktan sonra {target}'e geçer",
                "{node} re-initialized from {source} (by {user})",
                "{node}, {source} üzerinden yeniden oluşturuldu ({user} tarafından)",
                "{node} re-initialized from {source}", "{node}, {source} üzerinden yeniden oluşturuldu",
                "primary", "birincil"));
        assertEquals("Otomatik failover pg-a1 -> pg-a3 tamamlandı",
                c.translate("Automatic failover pg-a1 -> pg-a3 completed", 0));
        assertEquals("Otomatik failover başlatılmadı: bakım modu açık",
                c.translate("Automatic failover not started: maintenance mode is on", 0));
        // placeholder names with digits ({primary2}: the same value used twice in the English text)
        assertEquals("VIP beklendiği gibi şu an pg-a1 üzerinde; pg-a1 durduktan sonra pg-a2'e geçer",
                c.translate("the VIP is on pg-a1 now, as expected; it moves to pg-a2 after pg-a1 stops", 0));
        // the longest template wins
        assertEquals("pg-dr1, pg-a1 üzerinden yeniden oluşturuldu (admin tarafından)",
                c.translate("pg-dr1 re-initialized from pg-a1 (by admin)", 0));
        // a single-word value is a name and stays as it is, even a node called "primary"
        assertEquals("primary, pg-a1 üzerinden yeniden oluşturuldu", c.translate("primary re-initialized from pg-a1", 0));
        assertEquals("not in the catalog", c.translate("not in the catalog", 0));
    }

    @Test
    void theLanguageCookieWinsOverTheBrowser() {
        assertEquals(Messages.ENGLISH, LanguageSetup.chosen(null, Locale.ENGLISH));
        // a browser language the console does not have gets English
        assertEquals(Messages.supported(ARABIC), LanguageSetup.chosen(null, Locale.forLanguageTag("ar-SA")));
        assertEquals(Messages.ENGLISH, LanguageSetup.chosen(null, Locale.JAPANESE));
    }

    private static Set<String> placeholders(Pattern p, String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
