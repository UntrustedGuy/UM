package untrusted.manager.um.tools;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class DotNetAssemblyWriterTest {
    private static byte[] assemble(String text) throws Exception {
        Method m = DotNetAssemblyWriter.class.getDeclaredMethod("assemble", String.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, text);
    }

    @Test public void assemblesBranchAndSwitchBodies() throws Exception {
        byte[] branch = assemble("IL_0000: br IL_0006\nIL_0005: ret\nIL_0006: ret");
        assertEquals(7, branch.length);
        byte[] sw = assemble("IL_0000: switch [IL_0010, IL_0020]\nIL_000D: ret\nIL_0010: ret\nIL_0020: ret");
        assertEquals(16, sw.length);
    }

    @Test public void preservesMetadataTokensInRoundTripAssembly() throws Exception {
        byte[] body = assemble("IL_0000: ldstr 0x70000001 /* \"hello\" */\nIL_0005: pop\nIL_0006: ret");
        assertEquals(7, body.length);
        assertEquals((byte) 0x72, body[0]);
        assertEquals(0x01, body[1] & 0xff);
        assertEquals(0x00, body[2] & 0xff);
        assertEquals(0x00, body[3] & 0xff);
        assertEquals(0x70, body[4] & 0xff);
    }

    @Test public void buildsFatBodyForRelocation() throws Exception {
        DotNetAssemblyParser.MethodBodyInfo old = new DotNetAssemblyParser.MethodBodyInfo(
                0, 12, 12, 2, 8, 0x3013, 0x11000001, false, false, true);
        Method m = DotNetAssemblyWriter.class.getDeclaredMethod("buildFatMethodBody", DotNetAssemblyParser.MethodBodyInfo.class, byte[].class);
        m.setAccessible(true);
        byte[] body = (byte[]) m.invoke(null, old, new byte[]{0x16, 0x2a});
        assertEquals(16, body.length);
        assertEquals(0x13, body[0] & 0xff);
        assertEquals(0x30, body[1] & 0xff);
        assertEquals(0x08, body[2] & 0xff);
        assertEquals(0x01, body[8] & 0xff);
        assertEquals(0x11, body[9] & 0xff);
    }
}
