package com.bisoft.bfm.watcher.i18n;

import com.bisoft.bfm.common.Lsn;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The watchers and agents of a Turkish or Arabic site may run with that default locale. Then "I".toLowerCase() is
 * the dotless "ı" (keys such as "op.runnıng", CSS classes such as "ınfo") and "%.1f" prints "1,5". Code must not
 * depend on the JVM's default locale for keys, names and machine-read text.
 */
class LocaleIndependenceTest {

    @Test
    void noCaseConversionOrSizeDependsOnTheDefaultLocale() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(Path.of(".."))) {
            for (Path m : modules.filter(p -> p.getFileName().toString().startsWith("bfm-")).toList()) {
                Path main = m.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        List<String> lines = Files.readAllLines(f);
                        for (int i = 0; i < lines.size(); i++) {
                            if (lines.get(i).contains(".toLowerCase()") || lines.get(i).contains(".toUpperCase()")) {
                                found.add(f.getFileName() + ":" + (i + 1));
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), found, "use toLowerCase(Locale.ROOT) / toUpperCase(Locale.ROOT)");

        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("1.5 MB", Lsn.bytes(1536L * 1024));
            assertEquals("0/A000000", Lsn.format(Lsn.parse("0/a000000")));
        } finally {
            Locale.setDefault(before);
        }
    }
}
