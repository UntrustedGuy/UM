package untrusted.manager.um.network;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.common.SSHException;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.FileAttributes;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** SFTP client using SSHJ. Host-key acceptance is explicit per profile. */
public final class SftpClient implements AutoCloseable {
    public static final class Entry {
        public final String name, path;
        public final boolean directory, symlink;
        public final long size, modified;
        Entry(String n, String p, boolean d, boolean s, long z, long m) { name=n; path=p; directory=d; symlink=s; size=z; modified=m; }
    }
    private final SSHClient ssh;
    private final SFTPClient sftp;
    private final String root;

    public SftpClient(String host, int port, String user, String password, String privateKey, String keyPassphrase, boolean acceptAnyHostKey, String initialPath) throws Exception {
        this(host, port, user, password, privateKey, keyPassphrase, acceptAnyHostKey, null, initialPath);
    }

    /** Creates an SFTP connection with optional pinned host-key fingerprint.
     * Fingerprints use SSHJ's accepted MD5/SHA-1/SHA-256 formats. */
    public SftpClient(String host, int port, String user, String password, String privateKey, String keyPassphrase,
                      boolean acceptAnyHostKey, String hostKeyFingerprint, String initialPath) throws Exception {
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("Host required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        if (user == null || user.trim().isEmpty()) throw new IllegalArgumentException("Username required");
        ssh = new SSHClient();
        String fp = hostKeyFingerprint == null ? "" : hostKeyFingerprint.trim();
        if (!fp.isEmpty()) {
            ssh.addHostKeyVerifier(fp);
        } else if (acceptAnyHostKey) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
        } else {
            throw new IllegalArgumentException("Host-key fingerprint required, or explicitly enable trust-any for first connection");
        }
        ssh.setConnectTimeout(15000);
        ssh.setTimeout(30000);
        ssh.connect(host, port);
        if (privateKey != null && !privateKey.trim().isEmpty()) {
            KeyProvider kp = ssh.loadKeys(privateKey, keyPassphrase == null || keyPassphrase.isEmpty() ? null : keyPassphrase);
            ssh.authPublickey(user, kp);
        } else {
            if (password == null) password = "";
            ssh.authPassword(user, password);
        }
        sftp = ssh.newSFTPClient();
        root = normalize(initialPath);
        sftp.stat(root);
    }

    public List<Entry> list(String path) throws Exception {
        String p = normalize(path);
        List<Entry> out = new ArrayList<>();
        for (RemoteResourceInfo info : sftp.ls(p)) {
            String n = info.getName();
            if (n.equals(".") || n.equals("..")) continue;
            FileAttributes a = info.getAttributes();
            boolean dir = a.getType().toString().contains("DIRECTORY");
            boolean link = a.getType().toString().contains("SYMBOLIC_LINK");
            String child = "/".equals(p) ? "/" + n : p + "/" + n;
            out.add(new Entry(n, child, dir, link, a.getSize(), a.getMtime()));
        }
        out.sort(Comparator.comparing((Entry e) -> !e.directory).thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public void download(String remote, File local) throws Exception {
        if (local == null) throw new IllegalArgumentException("Local file required");
        String rp = normalize(remote);
        FileAttributes attrs = sftp.stat(rp);
        File parent = local.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Cannot create local directory");
        File tmp = new File(local.getAbsolutePath() + ".umtmp-" + System.nanoTime());
        boolean promoted = false;
        try {
            sftp.get(rp, tmp.getAbsolutePath());
            if (!tmp.isFile() || tmp.length() != attrs.getSize()) throw new IOException("SFTP download size verification failed");
            if (local.exists() && !local.delete()) throw new IllegalStateException("Cannot replace local file");
            if (!tmp.renameTo(local)) throw new IllegalStateException("Cannot finalize download");
            promoted = true;
        } finally { if (!promoted && tmp.exists()) tmp.delete(); }
    }

    public void upload(File local, String remote) throws Exception {
        if (local == null || !local.isFile()) throw new IllegalArgumentException("Local file required");
        String rp = normalize(remote);
        sftp.put(local.getAbsolutePath(), rp);
        FileAttributes attrs = sftp.stat(rp);
        if (attrs.getSize() != local.length()) throw new IOException("SFTP upload size verification failed");
    }

    public void mkdir(String remote) throws Exception { sftp.mkdirs(normalize(remote)); }
    public void delete(String remote) throws Exception {
        String p = normalize(remote); if (p.equals(root)) throw new IllegalArgumentException("Cannot delete connection root");
        FileAttributes a = sftp.stat(p); if (a.getType().toString().contains("DIRECTORY")) sftp.rmdir(p); else sftp.rm(p);
    }
    public void rename(String from, String to) throws Exception { String a=normalize(from), b=normalize(to); if (a.equals(root)) throw new IllegalArgumentException("Cannot rename connection root"); sftp.rename(a,b); }
    public void copy(String from, String to) throws Exception {
        String src = normalize(from), dst = normalize(to);
        if (src.equals(root)) throw new IllegalArgumentException("Cannot copy connection root");
        if (src.equals(dst) || dst.startsWith(src + "/")) throw new IllegalArgumentException("Destination is inside source");
        FileAttributes attrs = sftp.stat(src);
        File staging = File.createTempFile(".um-sftp-copy-", ".part");
        try {
            if (attrs.getType().toString().contains("DIRECTORY")) {
                if (!staging.delete()) throw new IOException("Cannot prepare SFTP staging directory");
                if (!staging.mkdirs()) throw new IOException("Cannot create SFTP staging directory");
                downloadTree(src, staging);
                uploadTree(staging, dst);
            } else {
                sftp.get(src, staging.getAbsolutePath());
                if (staging.length() != attrs.getSize()) throw new IOException("SFTP copy download verification failed");
                sftp.put(staging.getAbsolutePath(), dst);
                FileAttributes copied = sftp.stat(dst);
                if (copied.getSize() != attrs.getSize()) throw new IOException("SFTP copy upload verification failed");
            }
        } finally { deleteLocalTree(staging); }
    }

    private void downloadTree(String remoteDir, File localDir) throws Exception {
        for (Entry e : list(remoteDir)) {
            File child = new File(localDir, e.name);
            if (e.directory) { if (!child.mkdirs() && !child.isDirectory()) throw new IOException("Cannot create staging directory"); downloadTree(e.path, child); }
            else if (!e.symlink) { sftp.get(e.path, child.getAbsolutePath()); if (child.length() != e.size) throw new IOException("SFTP staging verification failed: " + e.path); }
        }
    }

    private void uploadTree(File localDir, String remoteDir) throws Exception {
        sftp.mkdirs(remoteDir);
        File[] files = localDir.listFiles(); if (files == null) return;
        for (File f : files) {
            String remote = remoteDir.endsWith("/") ? remoteDir + f.getName() : remoteDir + "/" + f.getName();
            if (f.isDirectory()) uploadTree(f, remote);
            else { sftp.put(f.getAbsolutePath(), remote); if (sftp.stat(remote).getSize() != f.length()) throw new IOException("SFTP staging upload verification failed: " + remote); }
        }
    }

    private static void deleteLocalTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) deleteLocalTree(x); }
        f.delete();
    }
    public String getRoot() { return root; }
    private String normalize(String p) { if (p == null || p.isEmpty()) return "/"; if (p.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL path"); String x=p.replace('\\','/'); while(x.contains("//")) x=x.replace("//","/"); if(!x.startsWith("/")) x="/"+x; String[] parts=x.split("/"); StringBuilder b=new StringBuilder(); for(String part:parts){ if(part.isEmpty()||part.equals("."))continue; if(part.equals("..")) throw new IllegalArgumentException("Parent traversal is not allowed"); b.append('/').append(part); } return b.length()==0?"/":b.toString(); }
    @Override public void close(){ try{sftp.close();}catch(Exception ignored){} try{ssh.disconnect();}catch(Exception ignored){} try{ssh.close();}catch(Exception ignored){} }
}
