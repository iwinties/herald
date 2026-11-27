package io.github.iwinties.herald;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Protocol rules shared by the stream reader, settings validation and offline tests. */
final class Ntfy {
    private static final List<Cidr> PRIVATE = Arrays.asList(Cidr.parse("10.0.0.0/8"),
            Cidr.parse("172.16.0.0/12"), Cidr.parse("192.168.0.0/16"), Cidr.parse("fc00::/7"));
    private static final Map<String,Object> TAGS = Json.fields("warning", "⚠️", "rotating_light", "🚨",
            "white_check_mark", "✅", "heavy_check_mark", "✔️", "x", "❌", "bell", "🔔", "door", "🚪",
            "house", "🏠", "package", "📦", "camera", "📷", "tv", "📺", "fire", "🔥", "droplet", "💧",
            "zap", "⚡", "information_source", "ℹ️", "tada", "🎉", "+1", "👍", "-1", "👎",
            "loudspeaker", "📢", "calendar", "📅");
    private static final List<String> STATES = Arrays.asList("off", "connected", "connecting", "error", "unauthorized");
    private static final List<String> EVENTS = Arrays.asList("connecting", "connected", "error",
            "unauthorized", "redirect refused", "line dropped");

    static boolean serverAllowed(String value) {
        try { canonical(value); return true; }
        catch (Settings.Invalid invalid) { return false; }
    }

    static String canonical(String value) {
        try {
            URI u = URI.create(value);
            String host = u.getHost();
            if (host == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
                    || u.getPort() > 65535 || u.getRawAuthority().endsWith(":") || u.getRawPath().endsWith("/"))
                throw new Settings.Invalid("ntfy.server");
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            String literal = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
            if (!scheme.equals("https") && !(scheme.equals("http") && PRIVATE.stream().anyMatch(c -> c.matches(literal))))
                throw new Settings.Invalid("ntfy.server");
            return scheme + "://" + host + (u.getPort() < 0 ? "" : ":" + u.getPort()) + u.getRawPath();
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new Settings.Invalid("ntfy.server"); }
    }

    private static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(Character.forDigit((b & 255) >> 4, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static String stateKey(String server, String topic) { return hash(canonical(server) + "|" + topic); }

    static String since(int catchUp, String id, long time, long now) {
        if (catchUp == 0) return "";
        return id != null && id.matches("[A-Za-z0-9]{1,64}") && time > now - catchUp ? id : catchUp + "s";
    }

    static String httpState(int status) {
        if (status == 401 || status == 403) return "unauthorized";
        return status >= 200 && status < 300 ? "connected" : "error";
    }

    static long backoff(int attempt, int status, double jitter) {
        if (httpState(status).equals("unauthorized")) return -1;
        if (status == 429) return 60000;
        long seconds = Math.min(60, 5 << Math.min(4, attempt));
        return (long)(seconds * 1000 * (1 + jitter * 0.2));
    }

    static String worst(Collection<String> states) {
        int rank = 0;
        for (String state : states) rank = Math.max(rank, STATES.indexOf(state));
        return STATES.get(rank);
    }

    static String logLine(int index, String event, int status, Throwable error) {
        return "ntfy " + index + " " + (EVENTS.contains(event) ? event : "error")
                + (status > 0 ? " http " + status : "") + (error == null ? "" : " " + error.getClass().getSimpleName());
    }

    // null signals EOF, an empty string signals a discarded/empty line. Input must be buffered.
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean discard = false;
        for (int b; (b = in.read()) != -1;) {
            if (b == '\n') return discard ? "" : line.toString("UTF-8");
            if (line.size() == 16384) discard = true;
            else line.write(b);
        }
        return null;
    }

    static boolean handles(String topic, Map<String,Object> event) {
        Object id = event.get("id"), time = event.get("time");
        return "message".equals(event.get("event")) && topic.equals(event.get("topic"))
                && id instanceof String && ((String)id).matches("[A-Za-z0-9]{1,64}")
                && time instanceof Number && ((Number)time).doubleValue() == ((Number)time).longValue();
    }

    static final class Cursor {
        final LinkedHashSet<String> recent = new LinkedHashSet<>();
        String id;
        long time;

        Cursor(String saved) {
            Map<String,Object> record = Json.parse(saved);
            id = (String)record.getOrDefault("id", "");
            time = ((Number)record.getOrDefault("time", 0)).longValue();
            Object ids = record.get("recent");
            if (ids instanceof List) for (Object item : (List<?>)ids) if (item instanceof String) recent.add((String)item);
        }

        String advance(String topic, Map<String,Object> event) {
            if (!handles(topic, event)) return null;
            id = (String)event.get("id");
            time = ((Number)event.get("time")).longValue();
            recent.remove(id);
            recent.add(id);
            if (recent.size() > 256) recent.remove(recent.iterator().next());
            return Json.stringify(Json.fields("id", id, "time", time, "recent", new ArrayList<>(recent)));
        }
    }

    static Map<String,Object> card(String server, Map<String,Object> topic, Map<String,Object> event,
            int catchUp, long now, Collection<String> recent, Settings settings) {
        String name = (String)topic.get("topic");
        if (!handles(name, event) || recent.contains(event.get("id"))) return null;
        long time = ((Number)event.get("time")).longValue();
        Object expires = event.get("expires");
        if (expires instanceof Number && ((Number)expires).doubleValue() < now || catchUp > 0 && time < now - catchUp) return null;
        Object raw = event.get("priority");
        double priority = raw instanceof Number ? ((Number)raw).doubleValue() : 3;
        String category = (String)topic.get("category");
        if (priority == 5 && category.equals("general")) category = "reminder";
        String title = text(event, "title");
        if (title.trim().isEmpty()) title = (String)topic.get("label");
        if (title.trim().isEmpty()) title = "Notification";
        String icon = "none";
        Object tags = event.getOrDefault("tags", Collections.emptyList());
        if (tags instanceof List) for (Object tag : (List<?>)tags) {
            if (TAGS.containsKey(tag)) { icon = "emoji:" + TAGS.get(tag); break; }
        }
        int duration = (int)settings.effective(category).get("duration");
        Map<String,Object> doc = Json.fields("id", "nt_" + hash(canonical(server) + "|" + name + "|" + event.get("id")).substring(0, 32),
                "category", category, "title", title, "message", text(event, "message"), "icon", icon,
                "priority", priority == 1 || priority == 2 ? "low" : priority == 4 || priority == 5 ? "high" : "normal",
                "style", Json.fields("duration", duration == 0 ? 30 : Math.min(30, duration), "sound", "none"),
                "announce", priority == 5 && Boolean.TRUE.equals(topic.get("speak"))
                        ? Json.fields("timing", "now", "urgent", false, "chime", false) : false);
        if (topic.get("look") != null) doc.put("look", topic.get("look"));
        return doc;
    }

    private static String text(Map<String,Object> row, String key) {
        Object value = row.get(key);
        return value instanceof String ? (String)value : "";
    }
}
