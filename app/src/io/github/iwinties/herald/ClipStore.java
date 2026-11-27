package io.github.iwinties.herald;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The clip cache: one file per key in one directory, at most cap clips, oldest (by
 * last-modified time) removed first. Pure Java: no Android classes.
 * PLAN-20261125-herald-v1.
 */
final class ClipStore {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,159}");
    // The marker must contain a character outside KEY so every legal key counts toward the cap.
    static final String TMP = ".tmp~";
    static final long TMP_MAX_AGE_MS = 60000;

    private final File dir;
    private final int cap;

    ClipStore(File dir, int cap) {
        if (cap < 1) throw new IllegalArgumentException("cap must be at least 1");
        this.dir = dir;
        this.cap = cap;
    }

    static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    File file(String key) {
        if (!validKey(key)) throw new IllegalArgumentException("bad key");
        return new File(dir, key);
    }

    /** The clip's length in bytes, or -1 when it is not cached. */
    long size(String key) {
        File f = file(key);
        return f.isFile() ? f.length() : -1;
    }

    /** Marks a clip as just used, so pruning keeps it longest. */
    void touch(String key) {
        file(key).setLastModified(System.currentTimeMillis());
    }

    /** Writes a temporary file next to the clip, then renames it onto the clip; then prunes. */
    synchronized void put(String key, byte[] bytes) throws IOException {
        File target = file(key);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, key + TMP + System.nanoTime());
        boolean renamed = false;
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(bytes);
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(target)) throw new IOException("cannot rename onto " + key);
            renamed = true;
        } finally {
            if (!renamed) tmp.delete();
        }
        prune(key);
    }

    /** Number of clips (temporary files are not clips). */
    int count() {
        int n = 0;
        for (File f : list()) {
            if (isClip(f.getName())) n++;
        }
        return n;
    }

    private static boolean isClip(String name) {
        return !name.contains(TMP) && validKey(name);
    }

    private File[] list() {
        File[] files = dir.listFiles();
        return files == null ? new File[0] : files;
    }

    private static final class Entry {
        final File file;
        final long modified;

        Entry(File file) {
            this.file = file;
            this.modified = file.lastModified();
        }
    }

    /** Deletes stale temporary files, then the oldest clips until at most cap remain. */
    private void prune(String keep) {
        long now = System.currentTimeMillis();
        List<Entry> clips = new ArrayList<Entry>();
        for (File f : list()) {
            String name = f.getName();
            if (name.contains(TMP)) {
                if (now - f.lastModified() > TMP_MAX_AGE_MS) f.delete();
            } else if (validKey(name)) {
                clips.add(new Entry(f));
            }
        }
        int excess = clips.size() - cap;
        if (excess <= 0) return;
        Collections.sort(clips, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int c = Long.compare(a.modified, b.modified);
                return c != 0 ? c : a.file.getName().compareTo(b.file.getName());
            }
        });
        for (Entry e : clips) {
            if (excess == 0) break;
            if (e.file.getName().equals(keep)) continue;
            if (e.file.delete()) excess--;
        }
    }
}
