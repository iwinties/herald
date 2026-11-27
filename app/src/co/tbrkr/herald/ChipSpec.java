package co.tbrkr.herald;

import java.util.Map;
import java.util.function.Predicate;

final class ChipSpec {
    static final String POSITIONS = "top_left top top_right bottom_left bottom bottom_right";
    final String id, text, icon, color, priority, look, position;
    final long expiresAt;

    private ChipSpec(String id, Map<String, Object> doc, long seconds, boolean stored, Predicate<String> knownLook) {
        this.id = id;
        Object t = doc.getOrDefault("text", "");
        Object c = doc.getOrDefault("color", "#FFFFFF");
        if (!(t instanceof String) || ((String) t).length() > 40 || !(c instanceof String)
                || !((String) c).matches("#[0-9a-fA-F]{6}")) throw CardSpec.bad("chip");
        text = (String) t;
        color = (String) c;
        try {
            icon = Style.icon(doc.getOrDefault("icon", "none"), "");
            position = doc.get("position") == null ? null : Settings.choice(doc.get("position"), "", POSITIONS);
            Object requested = doc.get("look");
            if (requested != null && !(requested instanceof String)) throw CardSpec.bad("chip");
            look = (String) requested;
            if (!stored && look != null && !knownLook.test(look)) throw CardSpec.bad("look");
            priority = Settings.choice(doc.getOrDefault("priority", "normal"), "", "normal high");
            if (text.isEmpty() && icon.equals("none")) throw CardSpec.bad("chip");
            if (stored) {
                Object end = doc.get("expires_at");
                expiresAt = end == null ? 0 : ((Number) end).longValue();
            } else {
                int expires = Settings.integer(doc.getOrDefault("expires", 0), "", 0, 604800);
                expiresAt = expires == 0 ? 0 : seconds + expires;
            }
        } catch (Settings.Invalid invalid) { throw CardSpec.bad("chip"); }
    }

    static ChipSpec parse(String id, Map<String, Object> doc, long seconds, boolean stored, Predicate<String> knownLook) {
        if (!CardSpec.key(id)) throw CardSpec.bad("chip");
        return new ChipSpec(id, doc, seconds, stored, knownLook);
    }

    Map<String, Object> row() {
        return Json.fields("id", id, "text", text, "icon", icon, "color", color, "priority", priority,
                "look", look, "position", position, "expires_at", expiresAt == 0 ? null : expiresAt);
    }
}
