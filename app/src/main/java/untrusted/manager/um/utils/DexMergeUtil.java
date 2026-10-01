package untrusted.manager.um.utils;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DexMergeUtil {

    private DexMergeUtil() {
    }

    public static void mergeDexFiles(List<File> inputs, File out, int api) throws IOException {
        DexPool pool = new DexPool(Opcodes.forApi(api));
        Set<String> seen = new HashSet<>();
        for (File f : inputs) {
            DexBackedDexFile dex = DexFileFactory.loadDexFile(f, null);
            for (ClassDef c : dex.getClasses()) {
                if (!seen.add(c.getType())) {
                    throw new IOException("Duplicate class while merging DEX files: " + c.getType());
                }
                pool.internClass(c);
            }
        }
        MemoryDataStore store = new MemoryDataStore();
        pool.writeTo(store);
        byte[] data = Arrays.copyOf(store.getData(), store.getSize());
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create DEX output directory: " + parent);
        }
        File temp = File.createTempFile(".um-dex-merge-", ".tmp", parent);
        boolean committed = false;
        try {
            try (OutputStream os = new FileOutputStream(temp)) {
                os.write(data);
                os.flush();
            }
            if (temp.length() != data.length || data.length < 112 || data[0] != 'd' || data[1] != 'e' || data[2] != 'x' || data[3] != '\n') {
                throw new IOException("Merged DEX output failed validation");
            }
            if (out.exists() && !out.delete()) throw new IOException("Cannot replace existing merged DEX: " + out);
            if (!temp.renameTo(out)) throw new IOException("Cannot commit merged DEX: " + out);
            committed = true;
        } finally {
            if (!committed && temp.exists()) temp.delete();
        }
    }
}
