package com.bisoft.bfm.watcher.i18n;

import com.bisoft.bfm.common.json.Json;
import com.vaadin.flow.i18n.I18NProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The console's languages: English, and any other listed in {@code i18n/languages}.
 *
 * <p>Adding a language takes no code: its tag on a line of {@code i18n/languages}, {@code messages_<tag>.properties}
 * with every key of {@code messages.properties}, {@code lang.<tag>} (the language's name in itself) in every bundle,
 * and optionally {@code runtime-<tag>.json}. The language picker shows once there is more than one; a right-to-left
 * language lays the console out right to left.
 *
 * <p>Two kinds of text. The views' own texts have keys in {@code i18n/messages*.properties} (UTF-8) and come
 * through {@link #getTranslation}: a key missing in a language falls back to English, then to the key itself.
 * Arguments replace {@code {0}}, {@code {1}}... literally (no MessageFormat, so apostrophes need no escaping and
 * numbers keep Latin digits in every language).
 *
 * <p>Texts made outside the views (health, reasons, events, operation steps, pre-checks, errors) stay English in the
 * state, the REST API and the mails; {@link #runtime} translates them for the screen with the catalog
 * {@code i18n/runtime-<lang>.json}: English text or template ({@code "{node} is down"}) to its translation, the values
 * of a template translated again when the catalog knows them. A text the catalog does not know is shown in English.
 */
@Component
public class Messages implements I18NProvider {

    public static final Locale ENGLISH = Locale.ENGLISH;
    /** The console's languages, English first: from {@code i18n/languages}. */
    public static final List<Locale> LOCALES = languages("i18n/languages");
    /** Languages written right to left. */
    private static final java.util.Set<String> RIGHT_TO_LEFT = java.util.Set.of("ar", "fa", "he", "ur");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");
    private static final Pattern NUMBERED = Pattern.compile("\\{(\\d{1,2})}");
    /** Translated runtime texts kept per language; the least recently used go first. */
    private static final int CACHE_SIZE = 5_000;

    private final Map<String, Properties> bundles = new ConcurrentHashMap<>();
    private final Map<String, Catalog> catalogs = new ConcurrentHashMap<>();

    /** The supported language closest to {@code locale} (English for anything else). */
    public static Locale supported(Locale locale) {
        if (locale == null) {
            return ENGLISH;
        }
        for (Locale l : LOCALES) {
            if (l.getLanguage().equals(locale.getLanguage())) {
                return l;
            }
        }
        return ENGLISH;
    }

    /** Whether the language is written right to left (Arabic, Persian, Hebrew, Urdu). */
    public static boolean rightToLeft(Locale locale) {
        return locale != null && RIGHT_TO_LEFT.contains(locale.getLanguage());
    }

    /**
     * The languages a resource lists, one tag per line ({@code #} starts a comment); English is always there, first.
     */
    static List<Locale> languages(String resource) {
        List<Locale> out = new ArrayList<>(List.of(ENGLISH));
        try (InputStream in = Messages.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    String tag = line.replaceAll("#.*", "").trim();
                    if (tag.isEmpty()) {
                        continue;
                    }
                    Locale l = Locale.forLanguageTag(tag);
                    if (!l.getLanguage().isEmpty() && out.stream().noneMatch(x -> x.getLanguage().equals(l.getLanguage()))) {
                        out.add(l);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
        return List.copyOf(out);
    }

    /** The console has languages to choose from (the picker shows only then). */
    public static boolean severalLanguages() {
        return LOCALES.size() > 1;
    }

    @Override
    public List<Locale> getProvidedLocales() {
        return LOCALES;
    }

    @Override
    public String getTranslation(String key, Locale locale, Object... params) {
        String lang = supported(locale).getLanguage();
        String text = bundle(lang).getProperty(key);
        if (text == null && !"en".equals(lang)) {
            text = bundle("en").getProperty(key);
        }
        if (text == null) {
            return key;
        }
        return fill(text, params);
    }

    /**
     * {@code {0}}, {@code {1}}... replaced by the arguments' text in one pass, so an argument that itself contains
     * "{1}" (a name, an error text) is shown as it is.
     */
    static String fill(String text, Object... params) {
        if (params == null || params.length == 0) {
            return text;
        }
        Matcher m = NUMBERED.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 32);
        while (m.find()) {
            int i = Integer.parseInt(m.group(1));
            String v = i < params.length ? (params[i] == null ? "" : String.valueOf(params[i])) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A text made outside the views, for the screen in {@code locale}; unknown texts come back unchanged. */
    public String runtime(String text, Locale locale) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String lang = supported(locale).getLanguage();
        if ("en".equals(lang)) {
            return text;
        }
        return catalog(lang).translate(text, 0);
    }

    /** The keys of one language's bundle (tests). */
    public java.util.Set<String> keys(Locale locale) {
        return bundle(supported(locale).getLanguage()).stringPropertyNames();
    }

    /** The English templates of one language's runtime catalog (tests). */
    public java.util.Set<String> runtimeTemplates(Locale locale) {
        return catalog(supported(locale).getLanguage()).texts.keySet();
    }

    private Properties bundle(String lang) {
        return bundles.computeIfAbsent(lang, l -> {
            Properties p = new Properties();
            String name = "en".equals(l) ? "i18n/messages.properties" : "i18n/messages_" + l + ".properties";
            try (InputStream in = Messages.class.getClassLoader().getResourceAsStream(name)) {
                if (in != null) {
                    try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                        p.load(r);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + name, e);
            }
            return p;
        });
    }

    private Catalog catalog(String lang) {
        return catalogs.computeIfAbsent(lang, l -> {
            String name = "i18n/runtime-" + l + ".json";
            try (InputStream in = Messages.class.getClassLoader().getResourceAsStream(name)) {
                if (in == null) {
                    return new Catalog(Map.of());
                }
                Map<String, Object> raw = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).raw();
                Map<String, String> texts = new java.util.LinkedHashMap<>();
                raw.forEach((k, v) -> texts.put(k, String.valueOf(v)));
                return new Catalog(texts);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + name, e);
            }
        });
    }

    /** English text or template → translation; templates are tried longest first. */
    static final class Catalog {
        /** A template with its literal start and end, checked before the regex (most texts fail there). */
        private record Template(String prefix, String suffix, Pattern rx, List<String> names, String translation) {
        }

        final Map<String, String> texts;
        private final List<Template> templates = new ArrayList<>();
        private final Map<String, String> cache = java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<>(256, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                        return size() > CACHE_SIZE;
                    }
                });

        Catalog(Map<String, String> texts) {
            this.texts = texts;
            List<String> withPlaceholders = new ArrayList<>(texts.keySet().stream()
                    .filter(k -> PLACEHOLDER.matcher(k).find()).toList());
            withPlaceholders.sort((a, b) -> Integer.compare(b.length(), a.length()));
            for (String en : withPlaceholders) {
                Matcher m = PLACEHOLDER.matcher(en);
                StringBuilder rx = new StringBuilder();
                List<String> names = new ArrayList<>();
                int last = 0;
                int first = -1;
                while (m.find()) {
                    if (first < 0) {
                        first = m.start();
                    }
                    rx.append(Pattern.quote(en.substring(last, m.start()))).append("(.+?)");
                    names.add(m.group(1));
                    last = m.end();
                }
                rx.append(Pattern.quote(en.substring(last)));
                templates.add(new Template(en.substring(0, first), en.substring(last),
                        Pattern.compile(rx.toString(), Pattern.DOTALL), names, texts.get(en)));
            }
        }

        String translate(String text, int depth) {
            if (depth == 0) {
                String hit = cache.get(text);
                if (hit != null) {
                    return hit;
                }
            }
            String out = texts.get(text);
            if (out == null) {
                out = text;
                for (Template t : templates) {
                    if (!text.startsWith(t.prefix()) || !text.endsWith(t.suffix())) {
                        continue;
                    }
                    Matcher m = t.rx().matcher(text);
                    if (m.matches()) {
                        Map<String, String> values = new java.util.HashMap<>();
                        for (int i = 0; i < t.names().size(); i++) {
                            values.put(t.names().get(i), value(m.group(i + 1), depth));
                        }
                        // one pass: a value that contains "{node}" is shown as it is
                        Matcher p = PLACEHOLDER.matcher(t.translation());
                        StringBuilder b = new StringBuilder();
                        while (p.find()) {
                            p.appendReplacement(b, Matcher.quoteReplacement(values.getOrDefault(p.group(1), p.group())));
                        }
                        p.appendTail(b);
                        out = b.toString();
                        break;
                    }
                }
            }
            if (depth == 0) {
                cache.put(text, out);
            }
            return out;
        }

        /**
         * A template's value: a phrase ("maintenance mode is on") is translated again, a single word is not, so node,
         * slot and host names stay what watcher.yml says even when one is called "primary".
         */
        private String value(String v, int depth) {
            return depth < 2 && v.indexOf(' ') >= 0 ? translate(v, depth + 1) : v;
        }
    }
}
