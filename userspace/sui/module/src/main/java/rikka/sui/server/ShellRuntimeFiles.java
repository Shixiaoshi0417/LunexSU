/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2026 Sui Contributors
 */

package rikka.sui.server;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.security.SecureRandom;

/**
 * Root-side access to the shell runtime directory. UID 2000 owns that directory (and
 * {@code /data/local/tmp}), so root opens it once without following a link and then reaches each
 * entry through {@code /proc/self/fd}, never through a path the shell user can redirect. Writes go
 * to a fresh exclusive temporary entry that is renamed over the target; reads refuse links, FIFOs
 * and oversized files.
 */
final class ShellRuntimeFiles {
    static final int SHELL_UID = 2000;
    private static final int TEMP_NAME_ATTEMPTS = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShellRuntimeFiles() {}

    /** An open, validated directory; entries are addressed relative to it. */
    static final class Dir implements Closeable {
        private final ParcelFileDescriptor fd;

        private Dir(ParcelFileDescriptor fd) {
            this.fd = fd;
        }

        String entry(String name) {
            if (name.isEmpty() || name.indexOf('/') >= 0 || ".".equals(name) || "..".equals(name)) {
                throw new IllegalArgumentException("invalid entry name: " + name);
            }
            return "/proc/self/fd/" + fd.getFd() + "/" + name;
        }

        @Override
        public void close() throws IOException {
            fd.close();
        }
    }

    /**
     * Opens {@code parent/name} without following a link at either component, creating
     * {@code name} with mode 0755 when {@code create} is set. The directory must be owned by root
     * or the shell user.
     */
    static Dir openDir(String parent, String name, boolean create) throws ErrnoException, IOException {
        try (Dir base = new Dir(openDirectory(parent))) {
            String path = base.entry(name);
            if (create) {
                try {
                    Os.mkdir(path, 0755);
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.EEXIST) throw e;
                }
            }
            ParcelFileDescriptor fd = openDirectory(path);
            StructStat stat = Os.fstat(fd.getFileDescriptor());
            if (stat.st_uid != 0 && stat.st_uid != SHELL_UID) {
                fd.close();
                throw new IOException("unexpected owner " + stat.st_uid + " of " + parent + "/" + name);
            }
            return new Dir(fd);
        }
    }

    private static ParcelFileDescriptor openDirectory(String path) throws ErrnoException, IOException {
        // O_DIRECTORY is not exposed by Android's public OsConstants API. Open without
        // blocking or following links, then validate the pinned descriptor before use.
        FileDescriptor raw = Os.open(path, OsConstants.O_RDONLY
                | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
        try {
            StructStat stat = Os.fstat(raw);
            if (!OsConstants.S_ISDIR(stat.st_mode)) {
                throw new IOException("not a directory: " + path);
            }
            return ParcelFileDescriptor.dup(raw);
        } finally {
            Os.close(raw);
        }
    }

    /**
     * Returns the content of a regular file entry, or {@code null} when it does not exist. Links,
     * FIFOs and other special files, and files above {@code maxSize}, are refused without blocking.
     */
    static byte[] readRegular(Dir dir, String name, int maxSize) throws ErrnoException, IOException {
        FileDescriptor fd;
        try {
            fd = Os.open(dir.entry(name), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return null;
            throw e;
        }
        try {
            StructStat stat = Os.fstat(fd);
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                throw new IOException("not a regular file: " + name);
            }
            if (stat.st_size > maxSize) {
                throw new IOException("file too large: " + name);
            }
            byte[] buffer = new byte[(int) stat.st_size];
            int length = 0;
            while (length < buffer.length) {
                int read = Os.read(fd, buffer, length, buffer.length - length);
                if (read <= 0) break;
                length += read;
            }
            if (length == buffer.length) return buffer;
            byte[] shorter = new byte[length];
            System.arraycopy(buffer, 0, shorter, 0, length);
            return shorter;
        } finally {
            Os.close(fd);
        }
    }

    /**
     * Atomically replaces {@code name} with {@code content}. The previous entry, which may be a
     * link planted by the shell user, is renamed over and never opened.
     */
    static void replace(Dir dir, String name, byte[] content, int mode) throws ErrnoException, IOException {
        String target = dir.entry(name);
        String temp = null;
        FileDescriptor fd = null;
        for (int attempt = 0; attempt < TEMP_NAME_ATTEMPTS && fd == null; attempt++) {
            temp = dir.entry("." + name + ".tmp-" + Long.toHexString(RANDOM.nextLong()));
            try {
                fd = Os.open(temp, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL
                        | OsConstants.O_NOFOLLOW, 0600);
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EEXIST) throw e;
            }
        }
        if (fd == null) {
            throw new IOException("no free temporary name for " + name);
        }
        boolean renamed = false;
        try {
            try {
                int offset = 0;
                while (offset < content.length) {
                    offset += Os.write(fd, content, offset, content.length - offset);
                }
                Os.fchmod(fd, mode);
                Os.fsync(fd);
            } finally {
                Os.close(fd);
            }
            Os.rename(temp, target);
            renamed = true;
        } finally {
            if (!renamed) {
                try {
                    Os.remove(temp);
                } catch (ErrnoException ignored) {
                }
            }
        }
    }
}
