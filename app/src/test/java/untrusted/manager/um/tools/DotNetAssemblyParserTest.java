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
}
