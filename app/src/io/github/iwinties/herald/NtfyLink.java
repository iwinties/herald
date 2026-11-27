package io.github.iwinties.herald;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Socket reads run independently. The control thread orders card delivery, cursor writes and retirement. */
final class NtfyLink {
    private final HandlerThread thread = new HandlerThread("herald-ntfy");
    private final Handler control;
    private final SharedPreferences prefs;
    private final Settings settings;
    private final Consumer<Map<String,Object>> post;
    private final List<Reader> readers = new ArrayList<>();
    private volatile Map<String,Object> desired;
    private volatile String status = "off";
    private boolean closed;

    NtfyLink(SharedPreferences prefs, Settings settings, Consumer<Map<String,Object>> post) {
        this.prefs = prefs;
        this.settings = settings;
        this.post = post;
        thread.start();
        control = new Handler(thread.getLooper());
    }

    synchronized void configure(Map<String,Object> group) {
        Map<String,Object> config = new LinkedHashMap<>(group);
        // The settings save callback supplies JSON numbers; startup supplies validated integers.
        config.put("catch_up", ((Number)group.get("catch_up")).intValue());
        if (closed || config.equals(desired) && !"unauthorized".equals(status)) return;
        desired = config;
        control.post(() -> {
            if (desired != config) return;
            retire();
            if (Boolean.TRUE.equals(config.get("enabled"))) {
                for (Map<String,Object> topic : Settings.topics(config)) {
                    Reader reader = new Reader(readers.size(), config, topic);
                    readers.add(reader);
                    reader.start();
                }
            }
            publish();
        });
    }

    String status() { return status; }

    synchronized void close() {
        closed = true;
        desired = null;
        control.post(() -> { retire(); publish(); thread.quitSafely(); });
    }

    private void retire() {
        for (Reader reader : readers) {
            reader.interrupt();
            HttpURLConnection c = reader.connection;
            if (c != null) c.disconnect();
        }
        readers.clear();
    }

    private void publish() {
        List<String> states = new ArrayList<>();
        for (Reader reader : readers) states.add(reader.state);
        status = Ntfy.worst(states);
    }

    private final class Reader extends Thread {
        final int index, catchUp;
        final Map<String,Object> config, topic;
        final String server, name, key;
        final Ntfy.Cursor cursor;
        String state = "connecting";
        volatile HttpURLConnection connection;

        Reader(int index, Map<String,Object> config, Map<String,Object> topic) {
            super("herald-ntfy-" + index);
            this.index = index;
            this.config = config;
            this.topic = topic;
            server = (String)config.get("server");
            name = (String)topic.get("topic");
            catchUp = ((Number)config.get("catch_up")).intValue();
            key = Ntfy.stateKey(server, name);
            cursor = new Ntfy.Cursor(prefs.getString(key, "{}"));
        }

        boolean active() { return desired == config && !isInterrupted(); }

        void report(String next, String event, int code, Throwable e) {
            if (!active()) return;
            Log.i("Herald", Ntfy.logLine(index, event, code, e));
            control.post(() -> {
                if (desired == config) { state = next; publish(); }
            });
        }

        // Wait for admission before reading another message, bounding queued work per subscription.
        void onControl(Runnable action) throws InterruptedException, ExecutionException {
            FutureTask<Void> task = new FutureTask<>(action, null);
            if (!control.post(task)) throw new InterruptedException();
            task.get();
        }

        void handle(Map<String,Object> event) {
            if (desired != config || !Ntfy.handles(name, event)) return;
            long now = System.currentTimeMillis() / 1000;
            Map<String,Object> card = Ntfy.card(server, topic, event, catchUp, now, cursor.recent, settings);
            if (card != null) post.accept(card);
            String record = cursor.advance(name, event);
            if (!prefs.edit().putString(key, record).commit()) throw new IllegalStateException("cursor write failed");
        }

        @Override public void run() {
            try { stream(); }
            catch (InterruptedException retired) { interrupt(); }
        }

        void stream() throws InterruptedException {
            int attempt = 0;
            while (active()) {
                int code = 0;
                long connectedAt = -1;
                try {
                    report("connecting", "connecting", 0, null);
                    String since = Ntfy.since(catchUp, cursor.id, cursor.time, System.currentTimeMillis() / 1000);
                    URL url = new URL(server + "/" + name + "/json" + (since.isEmpty() ? "" : "?since=" + since));
                    HttpURLConnection c = (HttpURLConnection)url.openConnection();
                    connection = c;
                    if (!active()) return;
                    c.setInstanceFollowRedirects(false);
                    c.setUseCaches(false);
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(90000);
                    String token = (String)config.get("token");
                    if (!token.isEmpty() && server.equals(config.get("token_server"))) c.setRequestProperty("Authorization", "Bearer " + token);
                    code = c.getResponseCode();
                    String next = Ntfy.httpState(code);
                    if (next.equals("connected")) {
                        connectedAt = SystemClock.elapsedRealtime();
                        report(next, next, code, null);
                        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream())) {
                            String line;
                            while (active() && (line = Ntfy.readLine(in)) != null) {
                                Map<String,Object> event;
                                try { event = Json.parse(line); }
                                catch (IllegalArgumentException invalid) {
                                    Log.i("Herald", Ntfy.logLine(index, "line dropped", 0, invalid));
                                    continue;
                                }
                                if (Ntfy.handles(name, event)) onControl(() -> handle(event));
                            }
                        }
                        report("error", "error", 0, null);
                    } else {
                        report(next, code >= 300 && code < 400 ? "redirect refused" : next, code, null);
                    }
                } catch (IOException | RuntimeException | ExecutionException failure) {
                    report("error", "error", code, failure);
                } finally {
                    if (connection != null) connection.disconnect();
                    connection = null;
                }
                if (!active()) return;
                if (connectedAt >= 0 && SystemClock.elapsedRealtime() - connectedAt >= 60000) attempt = 0;
                long delay = Ntfy.backoff(attempt, code, ThreadLocalRandom.current().nextDouble());
                if (delay < 0) return;
                Thread.sleep(delay);
                attempt = Math.min(attempt + 1, 4);
            }
        }
    }
}
