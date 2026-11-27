package io.github.iwinties.herald;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.function.Predicate;

/** The access-order index also breaks ties when several keys are used in one millisecond. */
final class ImageStore {
    static final int LIMIT = 2097152;
    private final File dir;
    private final Predicate<byte[]> decodes;
    private final LinkedHashMap<String, File> files = new LinkedHashMap<>(64, .75f, true);

    ImageStore(File dir, Predicate<byte[]> decodes) {
        this.dir = dir;
        this.decodes = decodes;
        File[] saved = dir.listFiles(f -> f.isFile() && CardSpec.key(f.getName()));
        if (saved != null) {
            Arrays.sort(saved, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
            for (File f : saved) files.put(f.getName(), f);
        }
    }

    static void checkKey(String key) { if (!CardSpec.key(key)) throw CardSpec.bad("key"); }

    synchronized int size(String key) { return (int) lookup(key).length(); }

    private File lookup(String key) {
        checkKey(key);
        File file = files.get(key);
        if (file == null || !file.isFile()) {
            files.remove(key);
            throw new Api.Failure(404, "not cached");
        }
        file.setLastModified(System.currentTimeMillis());
        return file;
    }

    synchronized byte[] read(String key) throws IOException {
        File file = lookup(key);
        if (file.length() > LIMIT) throw new IOException("oversized cached image");
        return Files.readAllBytes(file.toPath());
    }

    synchronized int count() { files.values().removeIf(f -> !f.isFile()); return files.size(); }

    synchronized void put(String key, byte[] bytes) {
        if (bytes.length > LIMIT) throw new Api.Failure(413, "body too large");
        checkKey(key);
        if (bytes.length == 0) throw new Api.Failure(400, "empty body");
        if (!decodes.test(bytes)) throw CardSpec.bad("image");
        File tmp = new File(dir, ".incoming");
        File dest = new File(dir, key);
        try {
            Files.createDirectories(dir.toPath());
            Files.write(tmp.toPath(), bytes);
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (files.size() >= 64 && !files.containsKey(key)) {
                String oldest = files.keySet().iterator().next();
                try { Files.deleteIfExists(new File(dir, oldest).toPath()); }
                catch (IOException evictionFailed) {
                    Files.deleteIfExists(dest.toPath());
                    throw evictionFailed;
                }
                files.remove(oldest);
            }
            files.put(key, dest);
        } catch (IOException e) { throw new Api.Failure(500, "store failed"); }
        finally { tmp.delete(); }
    }

    interface Fetch { byte[] read(String url) throws IOException; }
    static final class Picture<T> {
        final byte[] bytes;
        final T bitmap;
        Picture(byte[] bytes, T bitmap) { this.bytes = bytes; this.bitmap = bitmap; }
    }

    <T> Picture<T> load(CardSpec spec, Fetch fetch, java.util.function.Function<byte[], T> decode) throws IOException {
        byte[] bytes;
        if (spec.image.containsKey("url")) bytes = fetch.read((String) spec.image.get("url"));
        else if (spec.image.containsKey("key")) bytes = read((String) spec.image.get("key"));
        else bytes = (byte[]) spec.image.get("data");
        T bitmap = decode.apply(bytes);
        if (bitmap == null) throw new IOException("invalid bitmap");
        return new Picture<>(bytes, bitmap);
    }

    static int sampleSize(int width, int height) {
        if (width <= 0 || height <= 0) throw CardSpec.bad("image");
        int sample = 1;
        while (true) {
            long w = (width + (long) sample - 1) / sample;
            long h = (height + (long) sample - 1) / sample;
            if (w <= 1920 && h <= 1920 && w * h <= 2073600) return sample;
            sample *= 2;
        }
    }
}
