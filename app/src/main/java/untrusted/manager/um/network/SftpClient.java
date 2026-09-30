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
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("Host required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        if (user == null || user.trim().isEmpty()) throw new IllegalArgumentException("Username required");
        ssh = new SSHClient();
        if (acceptAnyHostKey) ssh.addHostKeyVerifier(new PromiscuousVerifier());
        else throw new IllegalArgumentException("A host-key verifier is required; enable explicit trust for first connection");
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
        File parent = local.getParentFile(); if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Cannot create local directory");
        File tmp = new File(local.getAbsolutePath() + ".umtmp-" + System.nanoTime());
        try { sftp.get(normalize(remote), tmp.getAbsolutePath()); if (!tmp.renameTo(local)) { if (local.exists() && !local.delete()) throw new IllegalStateException("Cannot replace local file"); if (!tmp.renameTo(local)) throw new IllegalStateException("Cannot finalize download"); } }
        finally { if (tmp.exists()) tmp.delete(); }
    }

    public void upload(File local, String remote) throws Exception {
        if (local == null || !local.isFile()) throw new IllegalArgumentException("Local file required");
        sftp.put(local.getAbsolutePath(), normalize(remote));
    }

    public void mkdir(String remote) throws Exception { sftp.mkdirs(normalize(remote)); }
    public void delete(String remote) throws Exception {
        String p = normalize(remote); if (p.equals(root)) throw new IllegalArgumentException("Cannot delete connection root");
        FileAttributes a = sftp.stat(p); if (a.getType().toString().contains("DIRECTORY")) sftp.rmdir(p); else sftp.rm(p);
    }
    public void rename(String from, String to) throws Exception { String a=normalize(from), b=normalize(to); if (a.equals(root)) throw new IllegalArgumentException("Cannot rename connection root"); sftp.rename(a,b); }
    public void copy(String from, String to) throws Exception { throw new UnsupportedOperationException("SFTP server-side copy is not portable; use download/upload"); }
    public String getRoot() { return root; }
    private String normalize(String p) { if (p == null || p.isEmpty()) return "/"; if (p.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL path"); String x=p.replace('\\','/'); while(x.contains("//")) x=x.replace("//","/"); if(!x.startsWith("/")) x="/"+x; String[] parts=x.split("/"); StringBuilder b=new StringBuilder(); for(String part:parts){ if(part.isEmpty()||part.equals("."))continue; if(part.equals("..")) throw new IllegalArgumentException("Parent traversal is not allowed"); b.append('/').append(part); } return b.length()==0?"/":b.toString(); }
    @Override public void close(){ try{sftp.close();}catch(Exception ignored){} try{ssh.disconnect();}catch(Exception ignored){} try{ssh.close();}catch(Exception ignored){} }
}
