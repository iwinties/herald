package co.tbrkr.herald;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class CardSpec {
    final String id, category, title, message, from, priority;
    final Map<String, Object> image, video;
    final List<Map<String, Object>> buttons;
    final Style style;
    final boolean idGiven;

    static boolean key(Object v) {
        return v instanceof String && ((String) v).matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}");
    }

    static Api.Failure bad(String field) { return new Api.Failure(400, "bad " + field); }

    private static String text(Map<String, Object> doc, String field, int limit) {
        Object v = doc.getOrDefault(field, "");
        if (!(v instanceof String)) throw bad("json");
        String s = (String) v;
        if (s.length() <= limit) return s;
        return s.substring(0, Character.isHighSurrogate(s.charAt(limit - 1)) ? limit - 1 : limit);
    }

    private static boolean url(Object v, boolean video) {
        if (!(v instanceof String)) return false;
        try {
            URI u = URI.create((String) v);
            return u.getHost() != null && ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())
                    || video && "rtsp".equalsIgnoreCase(u.getScheme()));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static Map<String, Object> image(Object value) {
        Map<String, Object> m = object(value, "image");
        if (m.size() == 1) {
            if (key(m.get("key")) || url(m.get("url"), false)) return m;
            if (m.get("data") instanceof String) {
                try {
                    byte[] decoded = Base64.getDecoder().decode((String) m.get("data"));
                    if (decoded.length <= 1048576) return Json.fields("data", decoded);
                } catch (IllegalArgumentException invalid) { /* malformed base64 */ }
            }
        }
        throw bad("image");
    }

    static Map<String, Object> object(Object value, String error) {
        if (!(value instanceof Map)) throw bad(error);
        return Settings.object(value, error);
    }

    private static Map<String, Object> video(Object value) {
        Map<String, Object> m = object(value, "video");
        Object tcp = m.getOrDefault("tcp", true);
        if (!url(m.get("url"), true) || !(tcp instanceof Boolean)
                || !(m.getOrDefault("muted", true) instanceof Boolean)) throw bad("video");
        try {
            int timeout = Settings.integer(m.getOrDefault("timeout", 12), "", 3, 60);
            Map<String, Object> result = Json.fields("url", m.get("url"), "tcp", tcp, "timeout", timeout);
            if (m.containsKey("muted")) result.put("muted", m.get("muted"));
            return result;
        } catch (Settings.Invalid invalid) { throw bad("video"); }
    }

    private static List<Map<String, Object>> buttons(Object value) {
        if (!(value instanceof List) || ((List<?>) value).size() > 3) throw bad("buttons");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Object> ids = new HashSet<>();
        for (Object item : (List<?>) value) {
            Map<String, Object> b = object(item, "buttons");
            Object label = b.get("label"), id = b.get("id"), action = b.getOrDefault("action", "event");
            if (!key(id) || !ids.add(id) || !(label instanceof String) || ((String) label).isEmpty()
                    || ((String) label).length() > 24 || !("event".equals(action) || "expand".equals(action)
                    || "dismiss".equals(action))) throw bad("buttons");
            result.add(Json.fields("id", id, "label", label, "action", action));
        }
        return result;
    }

    CardSpec(Map<String, Object> doc, Settings settings) {
        title = text(doc, "title", 120);
        message = text(doc, "message", 1000);
        from = text(doc, "from", 40);
        Object key = doc.get("id");
        if (doc.containsKey("id") && !key(key)) throw bad("id");
        idGiven = key != null;
        id = key == null ? UUID.randomUUID().toString().replace("-", "").substring(0, 16) : (String) key;
        Object cat = doc.getOrDefault("category", "general");
        if (!(cat instanceof String) || !((String) cat).matches("[a-z0-9_]{1,32}")) throw bad("category");
        if (title.trim().isEmpty() && message.trim().isEmpty() && !doc.containsKey("image") && !doc.containsKey("video"))
            throw new Api.Failure(400, "empty card");
        image = doc.containsKey("image") ? image(doc.get("image")) : null;
        video = doc.containsKey("video") ? video(doc.get("video")) : null;
        buttons = buttons(doc.getOrDefault("buttons", java.util.Collections.emptyList()));
        Object p = doc.getOrDefault("priority", "normal");
        if (!(p instanceof String) || !((String) p).matches("low|normal|high|critical")) throw bad("priority");
        priority = (String) p;
        try {
            synchronized (settings) {
                category = settings.categoryId((String) cat);
                Object look = doc.get("look");
                if (doc.containsKey("look") && (!(look instanceof String) || !settings.hasLook((String) look))) throw bad("look");
                Style base = settings.resolve(category, (String) look, object(doc.getOrDefault("style", Json.fields()), "style"));
                style = doc.containsKey("icon") ? base.merge(Json.fields("icon", doc.get("icon")), "style") : base;
            }
        } catch (Settings.Invalid invalid) { throw bad("style"); }
    }

    private CardSpec(CardSpec source, String id, Style style) {
        this.id = id;
        this.style = style;
        idGiven = source.idGiven;
        category = source.category;
        title = source.title;
        message = source.message;
        from = source.from;
        priority = source.priority;
        image = source.image;
        video = source.video;
        buttons = source.buttons;
    }

    CardSpec withId(String id) { return new CardSpec(this, id, style); }

    CardSpec silent() { return new CardSpec(this, id, style.merge(Json.fields("sound", "none"), "style")); }

    boolean sticky() { return style.fields.get("duration").equals(0); }
    int rank() { return java.util.Arrays.asList("low", "normal", "high", "critical").indexOf(priority); }
}
