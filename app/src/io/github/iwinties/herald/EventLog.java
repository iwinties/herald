package io.github.iwinties.herald;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** A fresh cursor namespace per service instance. No lock is held during a socket write. */
final class EventLog {
    final String stream;
    private final LongSupplier clock;
    private final Deque<Map<String, Object>> ring = new ArrayDeque<>();
    private long seq;
    private int waiters;

    EventLog(LongSupplier clock, SecureRandom random) {
        this.clock = clock;
        stream = String.format(java.util.Locale.ROOT, "%016x", random.nextLong());
    }

    synchronized long latest() { return seq; }

    synchronized void add(String type, Map<String, Object> fields) {
        Map<String, Object> event = new java.util.LinkedHashMap<>(fields);
        event.put("seq", ++seq);
        event.put("type", type);
        event.put("time", clock.getAsLong());
        if (ring.size() == 256) ring.removeFirst();
        ring.addLast(java.util.Collections.unmodifiableMap(event));
        notifyAll();
    }

    synchronized Map<String, Object> await(long after, long waitMs) {
        if (after == seq && waitMs > 0) {
            if (waiters == 4) throw new Api.Failure(429, "too many waiters");
            long remaining = TimeUnit.MILLISECONDS.toNanos(waitMs);
            long deadline = System.nanoTime() + remaining;
            waiters++;
            try {
                while (seq == after && remaining > 0) {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                    remaining = deadline - System.nanoTime();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                waiters--;
            }
        }
        List<Object> events = new ArrayList<>();
        for (Map<String, Object> event : ring) {
            if ((long) event.get("seq") > after) events.add(event);
        }
        return Json.fields("ok", true, "stream", stream, "seq", seq, "events", events,
                "gap", after < seq - ring.size(), "reset", after > seq);
    }
}
