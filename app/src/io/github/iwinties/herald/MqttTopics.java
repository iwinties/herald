package io.github.iwinties.herald;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Broker-independent wire contract. Incoming commands use the same domain operations as Api. */
final class MqttTopics {
    static final String[] COMMANDS = {"card", "clear", "chip", "announce", "stop", "notify"};
    private static final String[] ENTITIES = {"notify/notify", "binary_sensor/screen_on", "binary_sensor/sound_playing",
            "binary_sensor/overlay_allowed", "sensor/quiet_seconds", "sensor/last_card", "event/button"};
    final String id, root;
    final boolean commands;

    private final LongSupplier clock;
    private final QuietGate quietGate = new QuietGate();
    private String lastState;
    private long lastStateAt;

    MqttTopics(String base, String id, boolean commands, LongSupplier clock) {
        this.clock = clock;
        this.id = id;
        this.root = base + "/" + id + "/";
        this.commands = commands;
    }

    static Map<String,Object> txt(String id, String version) { return Json.fields("id", id, "api", "1", "version", version); }

    String commandName(String topic, boolean retained) {
        if (!commands || retained || !topic.startsWith(root + "cmd/")) return null;
        String name = topic.substring(root.length() + 4);
        return Arrays.asList(COMMANDS).contains(name) ? name : null;
    }

    static Map<String,Object> run(String name, byte[] bytes, Backend backend, Settings settings, CardBoard cards, ChipBoard chips, RequestIds ids) {
        Map<String,Object> result = Json.fields("cmd", name, "ok", true, "error", null);
        int limit = 65536;
        if (name.equals("card") || name.equals("notify")) limit = 1572864;
        if (name.equals("announce")) limit = 3145728;
        if (bytes.length > limit) throw new Api.Failure(413, "body too large");
        if (name.equals("stop")) { backend.stop(); return result; }

        Map<String,Object> body;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (name.equals("notify")) {
                if (text.length() > 1000) throw new Api.Failure(400, "message too long");
                body = Json.fields("category", "general", "message", text);
            } else body = Json.parse(text);
        } catch (CharacterCodingException | IllegalArgumentException e) { throw CardSpec.bad("json"); }

        switch (name) {
            case "notify": case "card":
                Map<String,Object> answer = SpokenCards.post(new CardSpec(body, settings),
                        name.equals("card") && body.containsKey("announce"), body.get("announce"), settings, backend, cards, ids);
                if (name.equals("card") && answer.containsKey("announce")) result.put("announce", answer.get("announce"));
                break;
            case "announce": backend.announce(new AnnounceSpec(body, settings)); break;
            case "clear":
                if (body.size() != 1) throw CardSpec.bad("json");
                if (body.containsKey("id")) {
                    if (!CardSpec.key(body.get("id"))) throw CardSpec.bad("id");
                    cards.delete((String)body.get("id"));
                } else cards.clear(body);
                break;
            case "chip":
                if (!CardSpec.key(body.get("id"))) throw CardSpec.bad("id");
                String id = (String)body.get("id");
                if (body.containsKey("delete") && !(body.get("delete") instanceof Boolean)) throw CardSpec.bad("json");
                if (Boolean.TRUE.equals(body.get("delete"))) chips.delete(id);
                else chips.put(id, body);
                break;
            default: throw new IllegalArgumentException("unknown command");
        }
        return result;
    }

    interface Wire<E extends Exception> {
        void subscribe(String topic, int qos) throws E;
        void publish(String topic, String value, int qos, boolean retain) throws E;
    }

    <E extends Exception> void connected(Wire<E> wire, String state, long quietSeconds) throws E {
        if (commands) for (String cmd : COMMANDS) wire.subscribe(root + "cmd/" + cmd, 1);
        wire.publish(root + "availability", "online", 1, true);
        publishState(wire, state);
        publishQuiet(wire, quietSeconds);
    }

    static final class QuietGate {
        private long lastValue, lastSentAt;

        boolean due(long seconds, long now) {
            return seconds != lastValue && now - lastSentAt >= 30_000;
        }

        void published(long seconds, long now) {
            lastValue = seconds;
            lastSentAt = now;
        }
    }

    <E extends Exception> void sample(Wire<E> wire, String state, long quietSeconds) throws E {
        if (!state.equals(lastState) || clock.getAsLong() - lastStateAt >= 60_000)
            publishState(wire, state);
        if (quietGate.due(quietSeconds, clock.getAsLong()))
            publishQuiet(wire, quietSeconds);
    }

    private <E extends Exception> void publishState(Wire<E> wire, String state) throws E {
        wire.publish(root + "state", state, 1, true);
        lastState = state;
        lastStateAt = clock.getAsLong();
    }

    private <E extends Exception> void publishQuiet(Wire<E> wire, long seconds) throws E {
        wire.publish(root + "quiet", Long.toString(seconds), 0, true);
        quietGate.published(seconds, clock.getAsLong());
    }

    Map<String,String> configs(String prefix, String name, String model, String version) {
        String node = "herald_" + id;
        Map<String,Object> device = Json.fields("identifiers", Collections.singletonList(node), "name", name,
                "model", model, "manufacturer", "Herald", "sw_version", version);
        Map<String,String> configs = new LinkedHashMap<>();
        for (String entity : ENTITIES) {
            String[] parts = entity.split("/");
            String object = parts[1];
            Map<String,Object> config = Json.fields("name", object.replace('_', ' '), "unique_id", node + "_" + object,
                    "device", device, "availability_topic", root + "availability");
            if (object.equals("notify")) {
                config.put("command_topic", root + "cmd/notify");
                config.put("qos", 1);
            } else if (object.equals("button")) {
                config.put("state_topic", root + "button");
                config.put("event_types", Arrays.asList("button", "dismissed"));
            } else if (object.equals("quiet_seconds")) {
                config.put("state_topic", root + "quiet");
                config.put("value_template", "{{ value }}");
                config.put("unit_of_measurement", "s");
            } else {
                config.put("state_topic", root + "state");
                String expr = "value_json." + object;
                if (parts[0].equals("binary_sensor")) expr = "'ON' if " + expr + " else 'OFF'";
                if (object.equals("overlay_allowed")) config.put("entity_category", "diagnostic");
                if (object.equals("last_card")) expr = "value_json.last_card.id if value_json.last_card else ''";
                config.put("value_template", "{{ " + expr + " }}");
            }
            configs.put(prefix + "/" + parts[0] + "/" + node + "/" + object + "/config", Json.stringify(config));
        }
        return configs;
    }

    <E extends Exception> void clearOld(Wire<E> wire, String previous, String next) throws E {
        if (previous == null || previous.equals(next)) return;
        for (String topic : configs(previous, "", "", "").keySet()) wire.publish(topic, "", 1, true);
    }

    static Map<String,Object> button(Map<String,Object> event) {
        if ("card.button".equals(event.get("type")))
            return Json.fields("event_type", "button", "card", event.get("id"), "button", event.get("button"));
        if ("card.ended".equals(event.get("type")) && "dismissed".equals(event.get("reason")))
            return Json.fields("event_type", "dismissed", "card", event.get("id"));
        return null;
    }
}
