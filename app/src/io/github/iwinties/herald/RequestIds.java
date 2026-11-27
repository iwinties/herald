package io.github.iwinties.herald;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One service owns this journal. A failed write never publishes a partial candidate. */
final class RequestIds {
    private boolean pendingFinalWrite;
    private final File file;
    private final LongSupplier clock;
    private Map<String,Map<String,Object>> records;

    RequestIds(File file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        try { load(); } catch (Api.Failure unavailable) { /* retry before admitting an id */ }
    }

    private static Api.Failure failed() { return new Api.Failure(500, "store failed"); }

    private void load() {
        if (records != null) return;
        Map<String,Map<String,Object>> next = new LinkedHashMap<>();
        if (Files.notExists(file.toPath())) {
            records = next;
            return;
        }
        try {
            Object parsed = Json.parseValue(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            boolean changed = false;
            for (Object item : (List<?>) parsed) {
                Map<String,Object> row = Settings.object(item, "record");
                String id = (String) row.get("id"), state = (String) row.get("state");
                if (!CardSpec.key(id) || !(row.get("job") instanceof String)
                        || !state.matches("queued|playing|played|stopped|error|interrupted")) throw failed();
                if (expired(row) || state.equals("queued")) { changed = true; continue; }
                if (state.equals("playing")) { row.put("state", "interrupted"); changed = true; }
                if (next.put(id, row) != null) throw failed();
            }
            if (changed) save(next);
            else records = next;
        } catch (IOException | RuntimeException invalid) { throw failed(); }
    }

    private boolean expired(Map<String,Object> row) {
        return clock.getAsLong() - ((Number) row.get("at")).longValue() >= 600000;
    }

    private void save(Map<String,Map<String,Object>> next) {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(Json.stringify(new ArrayList<>(next.values())).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) throw failed();
            records = next;
            pendingFinalWrite = false;
        } catch (IOException | SecurityException e) { throw failed(); }
        finally { tmp.delete(); }
    }

    synchronized Map<String,Object> find(String id) {
        load();
        records.values().removeIf(this::expired);
        Map<String,Object> row = records.get(id);
        if (row != null && pendingFinalWrite) {
            try { save(records); } catch (Api.Failure unavailable) { /* answer the known outcome */ }
        }
        return row == null ? null : new LinkedHashMap<>(row);
    }

    synchronized boolean full() {
        load();
        records.values().removeIf(this::expired);
        return records.size() >= 256;
    }

    static Map<String,Object> cardFields(Map<String,Object> answer) {
        return Json.fields("id", answer.get("id"), "category", answer.get("category"),
                "state", answer.get("state"), "reason", answer.get("reason"));
    }

    synchronized void put(String id, String job, Map<String,Object> cardAnswer) {
        load();
        Map<String,Object> row = Json.fields("id", id, "job", job, "state", "queued", "at", clock.getAsLong());
        if (cardAnswer != null) row.put("card", cardFields(cardAnswer));
        Map<String,Map<String,Object>> next = new LinkedHashMap<>(records);
        next.put(id, row);
        save(next);
    }

    synchronized void remove(String id) {
        load();
        if (!records.containsKey(id)) return;
        Map<String,Map<String,Object>> next = new LinkedHashMap<>(records);
        next.remove(id);
        save(next);
    }

    synchronized Map<String,Object> admit(String id, Runnable checks, BooleanSupplier busy, Consumer<String> enqueue) {
        Map<String,Object> row = id == null ? null : find(id);
        if (row != null) return Json.fields("ok", true, "job", row.get("job"), "outcome", row.get("state"), "duplicate", true);
        checks.run();
        if (id != null && full()) throw new Api.Failure(429, "too many request ids");
        if (busy.getAsBoolean()) throw new Api.Failure(429, "busy");
        String job = UUID.randomUUID().toString();
        if (id != null) put(id, job, null);
        enqueue.accept(job);
        return Json.fields("ok", true, "job", job);
    }

    synchronized void state(String id, String job, String state) {
        if (id == null) return;
        Map<String,Object> old = records.get(id);
        if (old == null || !job.equals(old.get("job"))) return;
        Map<String,Map<String,Object>> next = new LinkedHashMap<>(records);
        Map<String,Object> changed = new LinkedHashMap<>(old);
        changed.put("state", state);
        next.put(id, changed);
        try { save(next); }
        catch (Api.Failure unavailable) {
            // A final event already happened. Keep answering it in this process and retry
            // on the next write or lookup. A durable playing row still prevents crash replay.
            if (!"playing".equals(state)) {
                records = next;
                pendingFinalWrite = true;
            }
            throw unavailable;
        }
    }
}

/** All queue operations run under the service admission lock, including handing a job to the worker. */
final class AudioQueue<T> {
    private final ArrayDeque<T> waiting = new ArrayDeque<>();
    private T current;
    boolean full() { return waiting.size() >= (current == null ? 6 : 5); }
    boolean busy() { return current != null || !waiting.isEmpty(); }
    void add(T item) { waiting.addLast(item); }
    T start() { current = waiting.pollFirst(); return current; }
    void finished() { current = null; }

    List<T> stop(Consumer<T> cancelCurrent) {
        if (current != null) cancelCurrent.accept(current);
        List<T> drained = new ArrayList<>(waiting);
        waiting.clear();
        return drained;
    }
}
