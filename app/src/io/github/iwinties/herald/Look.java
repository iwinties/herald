package io.github.iwinties.herald;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Named visual overrides. The same immutable map is exposed and applied. */
final class Look {
    static final List<String> BUILTIN_IDS = Collections.unmodifiableList(Arrays.asList(
            "classic", "glass", "minimal", "banner", "alert", "pill"));
    static final Set<String> VISUAL_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "placement", "size", "text_size", "background", "opacity", "layout", "corner", "border", "border_color",
            "shadow", "glow", "accent_style", "title_color", "text_color", "title_weight", "label", "animation")));
    private static final Style DEFAULTS = Style.defaults("info", "#4FC3F7", 10, "queue", "medium", "normal");
    static final Map<String, Object> BASE;
    private static final Map<String, Look> BUILTINS = new LinkedHashMap<>();

    static {
        Map<String, Object> base = new LinkedHashMap<>(DEFAULTS.fields);
        base.keySet().retainAll(VISUAL_KEYS);
        BASE = Collections.unmodifiableMap(base);
        Map<String, Object> classic = new LinkedHashMap<>(base);
        classic.keySet().removeAll(Arrays.asList("placement", "size", "text_size", "animation"));
        builtin("classic", classic, Json.fields());
        builtin("glass", classic, Json.fields("corner", "large", "border", "thin", "border_color", "#5A6272",
                "shadow", "soft", "accent_style", "badge", "background", "#1C202A", "opacity", 60));
        builtin("minimal", classic, Json.fields("layout", "text", "corner", "none", "accent_style", "dot", "opacity", 0));
        builtin("banner", classic, Json.fields("layout", "strip", "placement", "top", "size", "full", "corner", "none",
                "shadow", "soft", "accent_style", "underline", "background", "#0C1018", "opacity", 80));
        builtin("alert", classic, Json.fields("placement", "center", "size", "large", "text_size", "large",
                "corner", "medium", "border", "thick", "border_color", "#FF6B6B", "shadow", "strong", "glow", "border",
                "accent_style", "badge", "background", "#781218", "opacity", 85, "text_color", "#FFE3E3", "label", "category"));
        builtin("pill", classic, Json.fields("layout", "pill", "placement", "top", "size", "medium", "corner", "pill",
                "border", "thin", "border_color", "#3A404C", "shadow", "soft", "accent_style", "badge",
                "background", "#181C24", "opacity", 80));
    }

    final String id, name;
    final Map<String, Object> style;

    private Look(String id, String name, Map<String, Object> style) {
        this.id = id;
        this.name = name;
        this.style = Collections.unmodifiableMap(style);
    }

    private static void builtin(String id, Map<String, Object> classic, Map<String, Object> changes) {
        Map<String, Object> style = new LinkedHashMap<>(classic);
        style.putAll(changes);
        BUILTINS.put(id, new Look(id, id, style));
    }

    static Look builtin(String id) { return BUILTINS.get(id); }

    static Look validate(String id, Map<String, Object> patch, String path) {
        Settings.rejectUnknown(patch, Json.fields("name", null, "style", null), path);
        Object name = patch.getOrDefault("name", id);
        if (!(name instanceof String) || ((String) name).isEmpty()
                || ((String) name).codePointCount(0, ((String) name).length()) > 40)
            throw new Settings.Invalid(path + ".name");
        String field = path + ".style";
        Map<String, Object> style = Settings.object(patch.get("style"), field);
        Settings.rejectUnknown(style, BASE, field);
        return new Look(id, (String) name, DEFAULTS.sparse(style, field));
    }

    Map<String, Object> document() { return Json.fields("id", id, "name", name, "style", style); }

    Map<String, Object> row() {
        Map<String, Object> result = document();
        result.put("builtin", BUILTINS.containsKey(id));
        return result;
    }
}
