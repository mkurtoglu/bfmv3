package com.bisoft.bfm.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LsnTest {

    @Test
    void parsesAndFormats() {
        long v = Lsn.parse("16/B374D848");
        assertEquals((0x16L << 32) | 0xB374D848L, v);
        assertEquals("16/B374D848", Lsn.format(v));
        assertEquals(-1, Lsn.parse(null));
    }

    @Test
    void diffNeverNegative() {
        assertEquals(Long.valueOf(30984L), Lsn.diff("0/13000318", "0/12FF8A10"));
        assertEquals(Long.valueOf(0L), Lsn.diff("0/1", "0/2"));
        assertNull(Lsn.diff(null, "0/2"));
        assertTrue(Lsn.compare("1/0", "0/FFFFFFFF") > 0);
    }

    @Test
    void formatsBytes() {
        assertEquals("0 B", Lsn.bytes(0L));
        assertEquals("30 KB", Lsn.bytes(30984L));
        assertEquals("2.1 GB", Lsn.bytes(2_254_857_830L));
    }
}
