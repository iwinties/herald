package co.tbrkr.herald;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLSocketFactory;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** The worker owns connections, discovery receipts and outgoing publications.
 * Paho delivers commands serially on its callback thread, applying backpressure until handled. */
final class MqttLink {
    private static final Logger PAHO = Logger.getLogger("org.eclipse.paho.client.mqttv3");
    private final HandlerThread thread = new HandlerThread("herald-mqtt");
    private final Handler worker;
    private final HeraldService service;
    private final SharedPreferences prefs;
    private final String id;
    private final EventLog events;
    private volatile Map<String,Object> wanted;
    private volatile boolean closed;
    private volatile String status = "off";
    private volatile Session active;
    private long retryAt;

    MqttLink(HeraldService service, SharedPreferences prefs, EventLog events, String id, Map<String,Object> mqtt) {
        PAHO.setLevel(Level.OFF);
        this.service = service;
        this.prefs = prefs;
        this.events = events;
        this.id = id;
        wanted = mqtt;
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::tick);
    }

    void configure(Map<String,Object> mqtt) { wanted = mqtt; }
    String status() { return status; }
    void close() {
        closed = true;
        worker.post(() -> { retire(false); thread.quit(); });
    }

    private String prefix(Map<String,Object> cfg) {
        return Boolean.TRUE.equals(cfg.get("discovery")) ? (String)cfg.get("discovery_prefix") : null;
    }

    private void tick() {
        if (closed) return;
        Map<String,Object> config = wanted;
        try {
            if (active != null && !active.config.equals(config)) {
                boolean obsolete = !enabled(config) || !Objects.equals(prefix(active.config), prefix(config))
                        || !Objects.equals(active.config.get("host"), config.get("host"))
                        || !Objects.equals(active.config.get("port"), config.get("port"))
                        || !Objects.equals(active.config.get("tls"), config.get("tls"));
                retire(obsolete);
                retryAt = 0;
            }
            if (!enabled(config)) status = "off";
            else if (active == null && now() >= retryAt) {
                status = "connecting";
                active = new Session(config);
                active.connect();
            } else if (active != null && active.ready && active.client.isConnected()) active.update();
        } catch (Exception failure) {
            failed();
        }
        worker.postDelayed(this::tick, 500);
    }

    private static long now() { return SystemClock.elapsedRealtime(); }
    private static boolean enabled(Map<String,Object> cfg) { return Boolean.TRUE.equals(cfg.get("enabled")); }
    private void failed() {
        status = "error";
        retire(false);
        retryAt = now() + 5000;
    }

    private void retire(boolean clearDiscovery) {
        Session old = active;
        active = null;
        if (old == null) return;
        try {
            if (old.client.isConnected()) {
                if (clearDiscovery) old.discovery(null);
                old.publish(old.topics.root + "availability", "offline", 1, true);
                old.client.disconnect(1000);
            }
        } catch (Exception unavailable) { /* A broken connection has an offline will. */ }
        try { old.client.disconnectForcibly(0, 1000, false); } catch (Exception ignored) {}
        try { old.client.close(true); } catch (Exception ignored) {}
    }

    private final class Session implements MqttCallbackExtended, MqttTopics.Wire<MqttException> {
        final Map<String,Object> config;
        final MqttClient client;
        final MqttTopics topics;
        final String discoveryKey;
        boolean ready;
        long cursor, lastSample;
        String discoveryName;

        Session(Map<String,Object> cfg) throws Exception {
            config = cfg;
            String host = (String)cfg.get("host");
            boolean tls = (boolean)cfg.get("tls");
            URI uri = new URI(tls ? "ssl" : "tcp", null, host, ((Number)cfg.get("port")).intValue(), null, null, null);
            if (uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("invalid host");
            client = new MqttClient(uri.toString(), "herald-" + id.substring(0,8), new MemoryPersistence());
            client.setTimeToWait(10000);
            topics = new MqttTopics((String)cfg.get("base_topic"), id, (boolean)cfg.get("commands"), MqttLink::now);
            discoveryKey = "mqtt_discovery_" + uri;
        }

        void connect() throws MqttException {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);
            options.setCleanSession(true);
            options.setWill(topics.root + "availability", "offline".getBytes(StandardCharsets.UTF_8), 1, true);
            String user = (String)config.get("username");
            if (!user.isEmpty()) options.setUserName(user);
            String password = (String)config.get("password");
            if (!password.isEmpty()) options.setPassword(password.toCharArray());
            if ((boolean)config.get("tls")) {
                options.setSocketFactory(SSLSocketFactory.getDefault());
                options.setHttpsHostnameVerificationEnabled(true);
            }
            client.setCallback(this);
            client.connect(options);
        }

        public void connectComplete(boolean reconnect, String uri) {
            worker.post(() -> {
                if (!current()) return;
                try {
                    cursor = events.latest();
                    topics.connected(this, service.mqttState(), service.quietSeconds());
                    lastSample = now();
                    discovery(prefix(config));
                    ready = true;
                    status = "connected";
                } catch (Exception failed) { failed(); }
            });
        }

        void discovery(String next) throws MqttException {
            String old = prefs.getString(discoveryKey, null);
            topics.clearOld(this, old, next);
            // Record the new prefix before the first config. A partial publish is cleaned on retry.
            if (!Objects.equals(old, next) && !prefs.edit().putString(discoveryKey, next).commit())
                throw new IllegalStateException("discovery prefix write failed");
            discoveryName = service.settings().name();
            if (next != null)
                for (Map.Entry<String,String> config : topics.configs(next, discoveryName, Build.MODEL, HeraldService.VERSION).entrySet())
                    publish(config.getKey(), config.getValue(), 1, true);
        }

        public void connectionLost(Throwable reason) {
            worker.post(() -> { if (current()) { ready = false; status = "connecting"; } });
        }
        public void deliveryComplete(IMqttDeliveryToken token) {}
        private boolean current() { return !closed && active == this && config.equals(wanted); }

        public void messageArrived(String topic, MqttMessage message) throws MqttException {
            String cmd = topics.commandName(topic, message.isRetained());
            if (cmd == null || !current()) return;
            Map<String,Object> result;
            try { result = service.command(cmd, message.getPayload()); }
            catch (Api.Failure failure) { result = Json.fields("cmd", cmd, "ok", false, "error", failure.getMessage()); }
            catch (RuntimeException failure) { result = Json.fields("cmd", cmd, "ok", false, "error", "command failed"); }
            if (current()) publish(topics.root + "result", Json.stringify(result), 0, false);
        }

        public void subscribe(String topic, int qos) throws MqttException { client.subscribe(topic, qos); }
        public void publish(String topic, String value, int qos, boolean retain) throws MqttException {
            client.publish(topic, value.getBytes(StandardCharsets.UTF_8), qos, retain);
        }

        void update() throws MqttException {
            if (!Objects.equals(discoveryName, service.settings().name())) discovery(prefix(config));
            for (Object row : (List<?>)events.await(cursor, 0).get("events")) {
                Map<String,Object> event = Settings.object(row, "event");
                publish(topics.root + "event", Json.stringify(event), 0, false);
                Map<String,Object> button = MqttTopics.button(event);
                if (button != null) publish(topics.root + "button", Json.stringify(button), 0, false);
                cursor = ((Number)event.get("seq")).longValue();
            }
            long time = now();
            if (time - lastSample < 2000) return;
            lastSample = time;
            topics.sample(this, service.mqttState(), service.quietSeconds());
        }
    }
}
