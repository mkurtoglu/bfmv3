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
 * The console's languages: English, Turkish and Arabic.
 *
 * <p>Two kinds of text. The views' own texts have keys in {@code i18n/messages*.properties} (UTF-8) and come
 * through {@link #getTranslation}: a key missing in a language falls back to English, then to the key itself.
 * Arguments replace {@code {0}}, {@code {1}}... literally (no MessageFormat, so the apostrophes of Turkish and English
 * text need no escaping, and numbers keep Latin digits in Arabic).
 *
 * <p>Texts made outside the views (health, reasons, events, operation steps, pre-checks, errors) stay English in the
 * state, the REST API and the mails; {@link #runtime} translates them for the screen with the catalog
 * {@code i18n/runtime-<lang>.json}: English text or template ({@code "{node} is down"}) to its translation, the values
 * of a template translated again when the catalog knows them. A text the catalog does not know is shown in English.
 */
@Component
public class Messages implements I18NProvider {

    public static final Locale ENGLISH = Locale.ENGLISH;
    public static final Locale TURKISH = Locale.forLanguageTag("tr");
    public static final Locale ARABIC = Locale.forLanguageTag("ar");
    public static final List<Locale> LOCALES = List.of(ENGLISH, TURKISH, ARABIC);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");

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

    /** Arabic is written right to left. */
    public static boolean rightToLeft(Locale locale) {
        return locale != null && "ar".equals(locale.getLanguage());
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

    /** {@code {0}}, {@code {1}}... replaced by the arguments' text. */
    static String fill(String text, Object... params) {
        if (params == null || params.length == 0) {
            return text;
        }
        String out = text;
        for (int i = 0; i < params.length; i++) {
            out = out.replace("{" + i + "}", params[i] == null ? "" : String.valueOf(params[i]));
        }
        return out;
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
    private static final class Catalog {
        private record Template(Pattern rx, List<String> names, String translation) {
        }

        final Map<String, String> texts;
        private final List<Template> templates = new ArrayList<>();
        private final Map<String, String> cache = new ConcurrentHashMap<>();

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
                while (m.find()) {
                    rx.append(Pattern.quote(en.substring(last, m.start()))).append("(.+?)");
                    names.add(m.group(1));
                    last = m.end();
                }
                rx.append(Pattern.quote(en.substring(last)));
                templates.add(new Template(Pattern.compile(rx.toString(), Pattern.DOTALL), names, texts.get(en)));
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
                    Matcher m = t.rx().matcher(text);
                    if (m.matches()) {
                        out = t.translation();
                        for (int i = 0; i < t.names().size(); i++) {
                            String v = m.group(i + 1);
                            out = out.replace("{" + t.names().get(i) + "}", depth < 2 ? translate(v, depth + 1) : v);
                        }
                        break;
                    }
                }
            }
            if (depth == 0 && cache.size() < 20_000) {
                cache.put(text, out);
            }
            return out;
        }
    }
}
