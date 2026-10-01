package untrusted.manager.um.tools;

import org.junit.Test;
import static org.junit.Assert.*;

public class DotNetAssemblyParserTest {
    @Test public void rejectsNonPeInput() {
        try {
            DotNetAssemblyParser.parse(new byte[]{'N','O','T','P','E'});
            fail("Expected invalid PE to be rejected");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("MZ"));
        }
    }

    @Test public void identifiesNativePeWithoutClrDirectory() throws Exception {
        byte[] pe = new byte[512];
        pe[0] = 'M'; pe[1] = 'Z';
        put32(pe, 0x3c, 0x80);
        put32(pe, 0x80, 0x00004550);
        put16(pe, 0x84, 0x8664); // AMD64
        put16(pe, 0x86, 0);      // no sections needed for the probe
        put16(pe, 0x94, 0x20b);  // PE32+
        DotNetAssemblyParser.Probe p = DotNetAssemblyParser.probe(pe);
        assertTrue(p.pe);
        assertFalse(p.managed);
        assertEquals(0, p.clrRva);
        assertEquals("x64", p.architecture());
    }

    private static void put16(byte[] b, int p, int v) {
        b[p]=(byte)v; b[p+1]=(byte)(v>>>8);
    }
    private static void put32(byte[] b, int p, int v) {
        b[p]=(byte)v; b[p+1]=(byte)(v>>>8); b[p+2]=(byte)(v>>>16); b[p+3]=(byte)(v>>>24);
    }
}
