package untrusted.manager.um.gameanalysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class GameAnalysisEngineTest {
    @Test public void parsesMinimalStandardMetadataAndCreatesDummyDll() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "um-game-analysis-test");
        delete(dir); assertTrue(dir.mkdirs());
        File metadata = new File(dir, "global-metadata.dat");
        int pairs = 31, header = 8 + pairs * 8;
        byte[] strings = "TestAssembly.dll\0".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[header + strings.length];
        putI32(data, 0, 0xFAB11BAF); putI32(data, 4, 29);
        // string table is the third pair in the standard v29 header.
        putI32(data, 24, header); putI32(data, 28, strings.length);
        System.arraycopy(strings, 0, data, header, strings.length);
        try (FileOutputStream out = new FileOutputStream(metadata)) { out.write(data); }

        GameAnalysisEngine.Result result = GameAnalysisEngine.analyze(null, metadata, null);
        assertTrue(result.report().contains("Standard IL2CPP metadata parsed"));
        File dump = new File(result.output(), "metadata");
        assertTrue(new File(dump, "dump.cs").isFile());
        assertTrue(new File(dump, "DummyDll/DummyAssembly.dll").isFile());
        delete(result.output()); delete(dir);
    }

    @Test public void rejectsInvalidMetadataWithoutFabricatingDump() throws Exception {
        File dir = Files.createTempDirectory("um-invalid-meta").toFile();
        File metadata = new File(dir, "bad.dat");
        try (FileOutputStream out = new FileOutputStream(metadata)) { out.write(new byte[]{1,2,3,4,5,6,7,8}); }
        GameAnalysisEngine.Result result = GameAnalysisEngine.analyze(null, metadata, null);
        assertTrue(result.report().contains("No valid standard metadata"));
        assertTrue(!new File(result.output(), "metadata/dump.cs").isFile());
        delete(result.output()); delete(dir);
    }

    private static void putI32(byte[] b,int p,int x){b[p]=(byte)x;b[p+1]=(byte)(x>>>8);b[p+2]=(byte)(x>>>16);b[p+3]=(byte)(x>>>24);}
    private static void delete(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)delete(x);}f.delete();}
}
