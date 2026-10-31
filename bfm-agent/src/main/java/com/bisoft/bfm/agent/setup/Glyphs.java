package com.bisoft.bfm.agent.setup;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Setup prints ✔ ! ✖ · … — on a terminal that cannot show them (LANG=C over SSH, say) they become plain ASCII
 * instead of question marks.
 */
final class Glyphs {

    private Glyphs() {
    }

    static PrintStream wrap(PrintStream out) {
        Charset cs = out.charset();
        if (cs.newEncoder().canEncode("✔✖·…—")) {
            return out;
        }
        return new PrintStream((OutputStream) out, true, cs) {
            @Override
            public void println(String x) {
                super.println(ascii(x));
            }

            @Override
            public void print(String x) {
                super.print(ascii(x));
            }
        };
    }

    static String ascii(String s) {
        return s == null ? null : s.replace("✔", "ok").replace("✖", "XX").replace("·", "-").replace("…", "...")
                .replace("—", "-").replace("→", "->");
    }

    static boolean utf8(PrintStream out) {
        return out.charset().equals(StandardCharsets.UTF_8);
    }
}
