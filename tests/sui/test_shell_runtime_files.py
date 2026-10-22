"""Exercise the root server's ShellRuntimeFiles against links, FIFOs and oversized files.

The production class is compiled unchanged against a host stand-in for android.system.Os. The
stand-in maps O_NOFOLLOW and O_CREAT|O_EXCL onto the JDK's NOFOLLOW_LINKS and CREATE_NEW, which
use the same Linux flags, and resolves /proc/self/fd/<n> to the directory that descriptor opened.
It models the syscalls the class relies on; it is not an Android runtime.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "userspace/sui/module/src/main/java/rikka/sui/server/ShellRuntimeFiles.java"
CONFIG = ROOT / "userspace/sui/module/src/main/java/rikka/sui/server/SuiConfigManager.java"
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "/nonexistent"))


def tool(name):
    candidate = JAVA_HOME / "bin" / name
    return str(candidate) if candidate.exists() else shutil.which(name)


JAVAC = tool("javac")
JAVA = tool("java")

STUBS = {
    "android/system/ErrnoException.java": r'''
package android.system;
public class ErrnoException extends Exception {
    public final int errno;
    public ErrnoException(String functionName, int errno) { super(functionName + " errno " + errno); this.errno = errno; }
}
''',
    "android/system/StructStat.java": r'''
package android.system;
public final class StructStat {
    public final int st_mode; public final int st_uid; public final long st_size;
    public StructStat(int mode, int uid, long size) { st_mode = mode; st_uid = uid; st_size = size; }
}
''',
    "android/system/OsConstants.java": r'''
package android.system;
public final class OsConstants {
    public static final int O_RDONLY = 0, O_WRONLY = 1, O_CREAT = 0100, O_EXCL = 0200,
            O_NONBLOCK = 04000, O_NOFOLLOW = 0400000;
    public static final int ENOENT = 2, EEXIST = 17, ENOTDIR = 20, EISDIR = 21, ELOOP = 40, EIO = 5;
    public static final int S_IFMT = 0170000, S_IFDIR = 0040000, S_IFREG = 0100000, S_IFLNK = 0120000,
            S_IFIFO = 0010000;
    public static boolean S_ISDIR(int m) { return (m & S_IFMT) == S_IFDIR; }
    public static boolean S_ISREG(int m) { return (m & S_IFMT) == S_IFREG; }
}
''',
    "android/os/ParcelFileDescriptor.java": r'''
package android.os;
import java.io.FileDescriptor;
public final class ParcelFileDescriptor implements java.io.Closeable {
    private final FileDescriptor fd; private final int number;
    ParcelFileDescriptor(FileDescriptor fd, int number) { this.fd = fd; this.number = number; }
    public static ParcelFileDescriptor dup(FileDescriptor orig) throws java.io.IOException {
        return android.system.Os.dupForTest(orig);
    }
    public int getFd() { return number; }
    public FileDescriptor getFileDescriptor() { return fd; }
    @Override public void close() { android.system.Os.closeForTest(fd); }
}
''',
    "android/system/Os.java": r'''
package android.system;
import android.os.ParcelFileDescriptor;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static android.system.OsConstants.*;
public final class Os {
    static final class Handle { Path path; SeekableByteChannel channel; }
    static final Map<FileDescriptor, Handle> OPEN = new IdentityHashMap<>();
    static final Map<Integer, Path> NUMBERS = new HashMap<>();
    static int next = 100;
    public static int shellUid = -1;  // the test user stands in for UID 2000
    public static final List<String> OPENED = new ArrayList<>();

    static Path resolve(String path) {
        if (path.startsWith("/proc/self/fd/")) {
            String rest = path.substring("/proc/self/fd/".length());
            int slash = rest.indexOf('/');
            Path dir = NUMBERS.get(Integer.parseInt(slash < 0 ? rest : rest.substring(0, slash)));
            if (dir == null) throw new IllegalStateException("closed descriptor in " + path);
            return slash < 0 ? dir : dir.resolve(rest.substring(slash + 1));
        }
        return Paths.get(path);
    }
    static ErrnoException errno(String call, Exception e) {
        if (e instanceof NoSuchFileException) return new ErrnoException(call, ENOENT);
        if (e instanceof FileAlreadyExistsException) return new ErrnoException(call, EEXIST);
        if (e instanceof DirectoryNotEmptyException) return new ErrnoException(call, EISDIR);
        String m = String.valueOf(e.getMessage());
        if (m.contains("symbolic links")) return new ErrnoException(call, ELOOP);
        if (m.contains("Is a directory")) return new ErrnoException(call, EISDIR);
        return new ErrnoException(call + ": " + e, EIO);
    }
    static int mode(Path p) throws java.io.IOException {
        PosixFileAttributes a = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        int type = a.isDirectory() ? S_IFDIR : a.isRegularFile() ? S_IFREG : a.isSymbolicLink() ? S_IFLNK : S_IFIFO;
        int bits = 0;
        for (PosixFilePermission perm : a.permissions()) bits |= 1 << (8 - perm.ordinal());
        return type | bits;
    }
    static FileDescriptor register(Path path, SeekableByteChannel channel) {
        FileDescriptor fd = new FileDescriptor();
        Handle h = new Handle(); h.path = path; h.channel = channel;
        OPEN.put(fd, h);
        return fd;
    }
    public static ParcelFileDescriptor dupForTest(FileDescriptor orig) {
        Handle h = OPEN.get(orig);
        FileDescriptor copy = register(h.path, null);
        int number = next++;
        NUMBERS.put(number, h.path);
        try {
            java.lang.reflect.Constructor<ParcelFileDescriptor> c =
                    ParcelFileDescriptor.class.getDeclaredConstructor(FileDescriptor.class, int.class);
            c.setAccessible(true);
            return c.newInstance(copy, number);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    public static void closeForTest(FileDescriptor fd) {
        Handle h = OPEN.remove(fd);
        if (h == null) return;
        try { if (h.channel != null) h.channel.close(); } catch (java.io.IOException ignored) {}
    }
    public static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        Path p = resolve(path);
        OPENED.add(path + " flags=" + Integer.toOctalString(flags));
        boolean nofollow = (flags & O_NOFOLLOW) != 0;
        try {
            if ((flags & O_CREAT) != 0) {
                if ((flags & O_EXCL) == 0) throw new AssertionError("non-exclusive create of " + path);
                // CREATE_NEW maps to O_CREAT|O_EXCL: it never opens an existing entry or link.
                SeekableByteChannel ch = Files.newByteChannel(p, EnumSet.of(StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE), PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
                return register(p, ch);
            }
            if (nofollow && Files.isSymbolicLink(p)) throw new ErrnoException("open", ELOOP);
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw new ErrnoException("open", ENOENT);
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(p)) return register(p, null);
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                // A FIFO without O_NONBLOCK would block the root server until a writer appears.
                if ((flags & O_NONBLOCK) == 0) throw new AssertionError("blocking open of special file " + path);
                return register(p, null);
            }
            Set<OpenOption> options = new HashSet<>();
            options.add((flags & O_WRONLY) != 0 ? StandardOpenOption.WRITE : StandardOpenOption.READ);
            if (nofollow) options.add(LinkOption.NOFOLLOW_LINKS);
            return register(p, Files.newByteChannel(p, options));
        } catch (ErrnoException e) { throw e; } catch (Exception e) { throw errno("open", e); }
    }
    public static StructStat fstat(FileDescriptor fd) throws ErrnoException {
        Handle h = OPEN.get(fd);
        try {
            int uid = (Integer) Files.getAttribute(h.path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (uid == shellUid) uid = 2000;
            return new StructStat(mode(h.path), uid, Files.size(h.path));
        } catch (Exception e) { throw errno("fstat", e); }
    }
    public static void close(FileDescriptor fd) { closeForTest(fd); }
    public static void mkdir(String path, int mode) throws ErrnoException {
        try { Files.createDirectory(resolve(path)); } catch (Exception e) { throw errno("mkdir", e); }
    }
    public static int read(FileDescriptor fd, byte[] b, int off, int len) throws ErrnoException {
        try { int r = OPEN.get(fd).channel.read(ByteBuffer.wrap(b, off, len)); return Math.max(r, 0); }
        catch (Exception e) { throw errno("read", e); }
    }
    public static int write(FileDescriptor fd, byte[] b, int off, int len) throws ErrnoException {
        try { return OPEN.get(fd).channel.write(ByteBuffer.wrap(b, off, len)); }
        catch (Exception e) { throw errno("write", e); }
    }
    public static void fchmod(FileDescriptor fd, int mode) throws ErrnoException {
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        for (PosixFilePermission perm : PosixFilePermission.values()) if ((mode & (1 << (8 - perm.ordinal()))) != 0) perms.add(perm);
        try { Files.setPosixFilePermissions(OPEN.get(fd).path, perms); } catch (Exception e) { throw errno("fchmod", e); }
    }
    public static void fsync(FileDescriptor fd) {}
    public static void rename(String from, String to) throws ErrnoException {
        Path target = resolve(to);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new ErrnoException("rename", EISDIR);
        // ATOMIC_MOVE is rename(2): a link at the target is replaced, not followed.
        try { Files.move(resolve(from), target, StandardCopyOption.ATOMIC_MOVE); } catch (Exception e) { throw errno("rename", e); }
    }
    public static void remove(String path) throws ErrnoException {
        try { Files.delete(resolve(path)); } catch (Exception e) { throw errno("remove", e); }
    }
}
''',
}

HARNESS = r'''
package rikka.sui.server;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
public class Check {
    static void check(boolean value, String what) { if (!value) throw new AssertionError(what); }
    static String read(Path p) throws Exception { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        Path base = Paths.get(args[0]);
        android.system.Os.shellUid = (Integer) Files.getAttribute(base, "unix:uid");
        Path outside = Files.write(base.resolve("outside"), "root secret".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(outside, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Path shell = Files.createDirectory(base.resolve("sui_shell_x"));

        // A planted link at the config name is replaced, never written through or chmodded.
        Files.createSymbolicLink(shell.resolve("sui_uids.txt"), outside);
        try (ShellRuntimeFiles.Dir dir = ShellRuntimeFiles.openDir(base.toString(), "sui_shell_x", false)) {
            ShellRuntimeFiles.replace(dir, "sui_uids.txt", "#transition:7\n10000:1\n".getBytes(StandardCharsets.UTF_8), 0644);
        }
        check(read(outside).equals("root secret"), "outside file rewritten");
        check(Files.getPosixFilePermissions(outside).toString().equals("[OWNER_READ, OWNER_WRITE]"), "outside chmodded");
        check(Files.isRegularFile(shell.resolve("sui_uids.txt"), LinkOption.NOFOLLOW_LINKS), "link kept");
        check(read(shell.resolve("sui_uids.txt")).startsWith("#transition:7"), "config content");
        try (DirectoryStream<Path> s = Files.newDirectoryStream(shell)) {
            int n = 0; for (Path ignored : s) n++;
            check(n == 1, "temporary entry left behind");
        }

        // Reads refuse links and FIFOs without blocking, and cap the size.
        try (ShellRuntimeFiles.Dir dir = ShellRuntimeFiles.openDir(base.toString(), "sui_shell_x", false)) {
            check(new String(ShellRuntimeFiles.readRegular(dir, "sui_uids.txt", 1024), StandardCharsets.UTF_8)
                    .startsWith("#transition:7"), "read back");
            check(ShellRuntimeFiles.readRegular(dir, "missing", 1024) == null, "missing is null");
            Files.delete(shell.resolve("sui_uids.txt"));
            Files.createSymbolicLink(shell.resolve("sui_uids.txt"), outside);
            boolean refused = false;
            try { ShellRuntimeFiles.readRegular(dir, "sui_uids.txt", 1024); } catch (android.system.ErrnoException e) { refused = e.errno == 40; }
            check(refused, "link read");
            Files.delete(shell.resolve("sui_uids.txt"));
            new ProcessBuilder("mkfifo", shell.resolve("sui_uids.txt").toString()).inheritIO().start().waitFor();
            refused = false;
            try { ShellRuntimeFiles.readRegular(dir, "sui_uids.txt", 1024); } catch (java.io.IOException e) { refused = true; }
            check(refused, "fifo read");
            Files.delete(shell.resolve("sui_uids.txt"));
            Files.write(shell.resolve("sui_uids.txt"), new byte[2048]);
            refused = false;
            try { ShellRuntimeFiles.readRegular(dir, "sui_uids.txt", 1024); } catch (java.io.IOException e) { refused = true; }
            check(refused, "oversized read");
        }

        // The directory itself may not be a link, at either the existing or the created path.
        Path outsideDir = Files.createDirectory(base.resolve("outside_dir"));
        Files.createSymbolicLink(base.resolve("sui_shell_link"), outsideDir);
        for (boolean create : new boolean[] {false, true}) {
            boolean refused = false;
            try { ShellRuntimeFiles.openDir(base.toString(), "sui_shell_link", create).close(); }
            catch (android.system.ErrnoException e) { refused = e.errno == 40; }
            check(refused, "directory link create=" + create);
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(outsideDir)) {
            check(!s.iterator().hasNext(), "wrote into the link target");
        }

        // A missing directory is created, and entry names cannot escape it.
        try (ShellRuntimeFiles.Dir dir = ShellRuntimeFiles.openDir(base.toString(), "sui_shell_new", true)) {
            ShellRuntimeFiles.replace(dir, "sui_uids.txt", new byte[] {'x'}, 0644);
            boolean refused = false;
            try { dir.entry("../outside"); } catch (IllegalArgumentException e) { refused = true; }
            check(refused, "escaping entry name");
        }
        check(read(base.resolve("sui_shell_new/sui_uids.txt")).equals("x"), "created");

        // Every create was exclusive and every other open refused links.
        for (String opened : android.system.Os.OPENED) {
            int flags = Integer.parseInt(opened.substring(opened.lastIndexOf('=') + 1), 8);
            check((flags & android.system.OsConstants.O_NOFOLLOW) != 0, "followed links: " + opened);
        }
        System.out.println("ok");
    }
}
'''


@unittest.skipUnless(os.name == "posix" and JAVAC and JAVA and shutil.which("mkfifo"),
                     "needs POSIX links/FIFOs and a JDK")
class ShellRuntimeFilesTests(unittest.TestCase):
    def test_root_server_never_follows_shell_owned_entries(self):
        with tempfile.TemporaryDirectory(prefix="sui-shell-files-") as temporary:
            folder = Path(temporary)
            sources = []
            for name, text in STUBS.items():
                path = folder / "src" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
                sources.append(str(path))
            harness = folder / "src/rikka/sui/server/Check.java"
            harness.parent.mkdir(parents=True, exist_ok=True)
            harness.write_text(HARNESS, encoding="utf-8")
            sources += [str(harness), str(SOURCE)]
            classes = folder / "classes"
            subprocess.run([JAVAC, "-encoding", "UTF-8", "-d", str(classes), *sources], check=True)
            work = folder / "work"
            work.mkdir()
            result = subprocess.run([JAVA, "-cp", str(classes), "rikka.sui.server.Check", str(work)],
                                    capture_output=True, text=True, timeout=60)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("ok", result.stdout.strip())


class ShellRuntimeFilesWiringTests(unittest.TestCase):
    def test_root_config_sync_goes_through_the_safe_helper(self):
        source = CONFIG.read_text(encoding="utf-8")
        start = source.index("private boolean syncUidsToShellFile(")
        sync = source[start:source.index("public long syncUidsToShellFileNow(")]
        self.assertIn("ShellRuntimeFiles.replace(", sync)
        for unsafe in ("FileOutputStream", "renameTo", "Os.chmod", "mkdirs"):
            self.assertNotIn(unsafe, sync)
        start = source.index("private long readShellTransitionId(")
        read = source[start:source.index("private File getShellDir(")]
        self.assertIn("ShellRuntimeFiles.readRegular(", read)
        self.assertNotIn("FileReader", read)


if __name__ == "__main__":
    unittest.main()
