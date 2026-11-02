package com.bisoft.bfm.watcher.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The console's three languages: complete bundles, keys the code uses, placeholders, the runtime catalog. */
class MessagesTest {

    private static final Pattern NUMBERED = Pattern.compile("\\{\\d+}");
    private static final Pattern NAMED = Pattern.compile("\\{[a-zA-Z_][a-zA-Z0-9_]*}");
    private static final Path UI_SOURCES = Path.of("src/main/java/com/bisoft/bfm/watcher");

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
        // an apostrophe needs no escaping (no MessageFormat), numbers keep Latin digits in Arabic
        assertTrue(messages.getTranslation("header.watchers", Messages.TURKISH, 2, 3, "w1").contains("Watcher'lar 2/3"));
        assertTrue(messages.getTranslation("ov.leaseLeft", Messages.ARABIC, 12).contains("12"));
        assertEquals("no.such.key", messages.getTranslation("no.such.key", Messages.TURKISH));
        // arguments go in once: a name that contains "{1}" is shown as it is
        assertEquals("Watchers 2/{1} · leader w1", messages.getTranslation("header.watchers", Messages.ENGLISH, 2,
                "{1}", "w1"));
        assertEquals(Messages.TURKISH, Messages.supported(Locale.forLanguageTag("tr-TR")));
        assertEquals(Messages.ENGLISH, Messages.supported(Locale.GERMAN));
        assertTrue(Messages.rightToLeft(Messages.ARABIC));
        assertFalse(Messages.rightToLeft(Messages.TURKISH));
    }

    @Test
    void runtimeTextsAreTranslatedByTemplateAndUnknownOnesStayEnglish() {
        // English is never touched; a text the catalog does not know comes back as it is
        assertEquals("Healthy", messages.runtime("Healthy", Messages.ENGLISH));
        assertEquals("some agent said something", messages.runtime("some agent said something", Messages.TURKISH));
        assertEquals(null, messages.runtime(null, Messages.ARABIC));
        for (Locale l : new Locale[] {Messages.TURKISH, Messages.ARABIC}) {
            Set<String> templates = messages.runtimeTemplates(l);
            if (templates.isEmpty()) {
                continue;
            }
            assertEquals(messages.runtimeTemplates(Messages.TURKISH), templates, "Turkish and Arabic know the same texts");
            for (String en : templates) {
                assertEquals(placeholders(NAMED, en), placeholders(NAMED, messages.runtime(en, l)), l + ": " + en);
            }
            if (templates.contains("Healthy")) {
                assertFalse(messages.runtime("Healthy", l).equals("Healthy"), l + ": Healthy");
            }
        }
        // events as the watcher writes them: the template's values are translated too, names stay as they are
        assertEquals("Otomatik failover pg-a1 -> pg-a3 tamamlandı",
                messages.runtime("Automatic failover pg-a1 -> pg-a3 completed", Messages.TURKISH));
        assertEquals("Otomatik failover başlatılmadı: bakım modu açık",
                messages.runtime("Automatic failover not started: maintenance mode is on", Messages.TURKISH));
        assertEquals("فشل التبديل {oldPrimary} -> {target}: {a}".replace("{oldPrimary}", "pg-a1").replace("{target}", "pg-a2")
                        .replace("{a}", "لا يزال الـ VIP يستجيب: x"),
                messages.runtime("Switchover pg-a1 -> pg-a2 failed: the VIP still answers: x", Messages.ARABIC));
        // placeholder names with digits ({primary2}: the same value used twice in the English text)
        assertEquals("VIP beklendiği gibi şu an pg-a1 üzerinde; pg-a1 durduktan sonra pg-a2'e geçer",
                messages.runtime("the VIP is on pg-a1 now, as expected; it moves to pg-a2 after pg-a1 stops", Messages.TURKISH));
        assertEquals("pg-dr1, pg-a1 üzerinden yeniden oluşturuldu (admin tarafından)",
                messages.runtime("pg-dr1 re-initialized from pg-a1 (by admin)", Messages.TURKISH));
        // a single-word value is a name and stays as it is, even a node called "primary"
        assertEquals("primary, pg-a1 üzerinden yeniden oluşturuldu",
                messages.runtime("primary re-initialized from pg-a1", Messages.TURKISH));
    }

    @Test
    void theLanguageCookieWinsOverTheBrowser() {
        assertEquals(Messages.ENGLISH, LanguageSetup.chosen(null, Locale.ENGLISH));
        assertEquals(Messages.ARABIC, LanguageSetup.chosen(null, Locale.forLanguageTag("ar-SA")));
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
