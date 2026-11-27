package io.github.iwinties.herald;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class Category {
    private static final Map<String, Category> BUILTINS = new LinkedHashMap<>();
    static {
        builtin("general", "General", "info", "#4FC3F7", 10, "queue");
        builtin("doorbell", "Doorbell", "door", "#FFB74D", 30, "replace");
        builtin("camera", "Camera", "camera", "#81C784", 30, "replace");
        builtin("alarm", "Alarm", "alarm", "#E57373", 0, "replace");
        builtin("media", "Media", "media", "#BA68C8", 12, "queue");
        builtin("announcement", "Announcement", "megaphone", "#FFF176", 15, "queue");
        builtin("reminder", "Reminder", "bell", "#90A4AE", 12, "queue");
        builtin("system", "System", "tv", "#B0BEC5", 30, "replace");
    }

    final String id, name, look;
    final boolean announce;
    final Map<String, Object> overrides;
    final boolean builtin;
    final Style style;

    private Category(String id, String name, boolean builtin, Style style, String look, boolean announce, Map<String, Object> overrides) {
        this.look = look;
        this.announce = announce;
        this.overrides = Collections.unmodifiableMap(overrides);
        this.id = id;
        this.name = name;
        this.builtin = builtin;
        this.style = style;
    }

    private static void builtin(String id, String name, String icon, String accent, int duration, String stacking) {
        Style style = Style.defaults(icon, accent, duration, stacking, id.equals("alarm") ? "large" : "medium",
                id.equals("reminder") ? "dim" : "normal");
        String look;
        switch (id) {
            case "alarm": look = "alert"; break;
            case "media": look = "pill"; break;
            case "camera": case "doorbell": look = "classic"; break;
            default: look = "glass";
        }
        BUILTINS.put(id, new Category(id, name, true, style, look, false, Collections.emptyMap()));
    }

    static Map<String, Category> defaults() { return new LinkedHashMap<>(BUILTINS); }
    static boolean isBuiltin(Object id) { return BUILTINS.containsKey(id); }
    static Category base(String id) {
        if (isBuiltin(id)) return BUILTINS.get(id);
        return new Category(id, id, false, BUILTINS.get("general").style, "glass", false, Collections.emptyMap());
    }

    Category merge(Map<String, Object> patch, String path, boolean allowRemovals) {
        Object label = patch.getOrDefault("name", name);
        if (!(label instanceof String)) throw new Settings.Invalid(path + ".name");
        Object nextLook = patch.getOrDefault("look", look);
        if (!(nextLook instanceof String)) throw new Settings.Invalid(path + ".look");
        Object speech = patch.getOrDefault("announce", announce);
        if (!(speech instanceof Boolean)) throw new Settings.Invalid(path + ".announce");
        Map<String, Object> updated = new LinkedHashMap<>(overrides);
        if (patch.containsKey("style")) {
            String field = path + ".style";
            Map<String, Object> changes = new LinkedHashMap<>(Settings.object(patch.get("style"), field));
            if (allowRemovals) {
                Settings.rejectUnknown(changes, style.fields, field);
                Iterator<Map.Entry<String, Object>> it = changes.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Object> change = it.next();
                    if (change.getValue() == null) {
                        updated.remove(change.getKey());
                        it.remove();
                    }
                }
            }
            updated.putAll(style.sparse(changes, field));
        }
        Map<String, Object> known = document();
        known.put("effective", null);
        Settings.rejectUnknown(patch, known, path);
        return new Category(id, (String) label, builtin, style, (String) nextLook, (boolean) speech, updated);
    }

    Map<String, Object> document() {
        return Json.fields("id", id, "name", name, "builtin", builtin, "look", look, "announce", announce, "style", overrides);
    }
}
