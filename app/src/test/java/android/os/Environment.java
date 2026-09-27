package android.os;
import java.io.File;
public final class Environment {
    public static File getExternalStorageDirectory() { return new File(System.getProperty("java.io.tmpdir"), "um-test-storage"); }
}
