package io.pgvip.agent.pg;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgHelpersTest {

    @Test
    void parsesPsqlOutputWithNullsAndSeparators() {
        String out = "t" + Psql.FS + "\\N" + Psql.FS + "a|b" + Psql.RS + "f" + Psql.FS + "x" + Psql.FS + "" + "\n";
        List<List<String>> rows = Psql.parse(out);
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("t", null, "a|b"), rows.get(0));
        assertEquals(Arrays.asList("f", "x", ""), rows.get(1));
        assertTrue(Psql.parse("\n").isEmpty());
    }

    @Test
    void quotesConninfoValuesThatNeedIt() {
        assertEquals("host=10.0.0.1", PgController.kv("host", "10.0.0.1"));
        assertEquals("passfile='/var/lib/my dir/.pgpass'", PgController.kv("passfile", "/var/lib/my dir/.pgpass"));
        assertEquals("password='it\\'s'", PgController.kv("password", "it's"));
    }

    @Test
    void quotesConfValues() {
        assertEquals("'host=a password=''x'''", PgController.confLiteral("host=a password='x'"));
        assertEquals("'it''s'", Psql.literal("it's"));
        assertNull(PgProbe.parseInt("x"));
    }
}
