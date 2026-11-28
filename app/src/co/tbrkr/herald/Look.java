package co.tbrkr.herald;

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
            "classic", "glass", "minimal", "banner", "alert", "pill", "paper-note", "night-shift", "on-air"));
    static final Set<String> VISUAL_KEYS;
    static {
        Set<String> keys = new LinkedHashSet<>(Arrays.asList(
            "placement", "size", "text_size", "background", "opacity", "layout", "corner", "border", "border_color",
            "shadow", "glow", "accent_style", "title_color", "text_color", "title_weight", "label", "animation"));
        keys.add("edge_margin");
        keys.addAll(MotionPresets.DEFAULTS.keySet());
        keys.addAll(Style.BUTTON_DEFAULTS.keySet());
        VISUAL_KEYS = Collections.unmodifiableSet(keys);
    }
    private static final Style DEFAULTS = Style.defaults("info", "#4FC3F7", 10, "queue", "medium", "normal");
    static final Map<String, Object> BASE;
    private static final Map<String, Look> BUILTINS = new LinkedHashMap<>();

    static {
        Map<String, Object> base = new LinkedHashMap<>(DEFAULTS.fields);
        base.keySet().retainAll(VISUAL_KEYS);
        BASE = Collections.unmodifiableMap(base);
        Map<String, Object> classic = new LinkedHashMap<>(base);
        classic.keySet().removeAll(Arrays.asList("placement", "size", "text_size", "animation"));
        classic.remove("edge_margin");
        classic.keySet().removeAll(MotionPresets.DEFAULTS.keySet());
        classic.keySet().removeAll(Style.BUTTON_DEFAULTS.keySet());
        builtin("classic", classic, Json.fields("shadow", "soft"));
        builtin("glass", classic, Json.fields("corner", "large", "border", "thin", "border_color", "#5A6272",
                "shadow", "soft", "accent_style", "badge", "background", "#1C202A", "opacity", 60));
        builtin("minimal", classic, Json.fields("transition_in", "fade", "transition_out", "fade", "layout", "text", "corner", "none", "accent_style", "dot", "opacity", 0));
        builtin("banner", classic, Json.fields("transition_in", "slide_top", "transition_out", "slide_top", "layout", "strip", "placement", "top", "size", "full", "corner", "none",
                "shadow", "soft", "accent_style", "underline", "background", "#0C1018", "opacity", 80));
        builtin("alert", classic, Json.fields("placement", "center", "size", "large", "text_size", "large",
                "corner", "medium", "border", "thick", "border_color", "#FF6B6B", "shadow", "strong", "glow", "border",
                "accent_style", "badge", "background", "#781218", "opacity", 85, "text_color", "#FFE3E3", "label", "category"));
        builtin("pill", classic, Json.fields("layout", "pill", "placement", "top", "size", "medium", "corner", "pill",
                "border", "thin", "border_color", "#3A404C", "shadow", "soft", "accent_style", "badge",
                "background", "#181C24", "opacity", 80));
        builtin("paper-note", "Paper Note", classic, Json.fields(
                "placement", "top_right", "background", "#F4EAD5", "opacity", 100,
                "corner", "small", "title_color", "#302B25", "text_color", "#50473D",
                "accent_style", "dot", "shadow", "soft", "button_style", "outline",
                "button_shape", "square", "button_background", "#DFD3BB", "button_text_color", "#423831",
                "button_focus_background", "#C3AF8C", "button_focus_text_color", "#28221C"));
        builtin("night-shift", "Night Shift", classic, Json.fields(
                "placement", "bottom_right", "background", "#142D2B", "opacity", 95,
                "corner", "large", "accent_style", "none", "border", "thin",
                "border_color", "#63817B", "title_color", "#D7F4E7", "text_color", "#BBD2CB",
                "shadow", "soft", "button_style", "raised", "button_shape", "pill",
                "button_background", "#253F3A", "button_text_color", "#D1E6DB", "button_focus_background", "#456658",
                "button_focus_text_color", "#F1F8EA"));
        builtin("on-air", "On Air", classic, Json.fields(
                "layout", "strip", "placement", "bottom", "size", "full",
                "edge_margin", 0, "text_animation", "ticker", "transition_in", "slide_bottom_fade", "transition_out", "slide_bottom", "background", "#222036",
                "opacity", 95, "corner", "none", "accent_style", "underline",
                "label", "none", "title_color", "#FAECD5", "text_color", "#DAD2E6",
                "shadow", "strong", "button_style", "underline", "button_shape", "square",
                "button_background", "#39344D", "button_text_color", "#DDD7E7", "button_focus_background", "#635473",
                "button_focus_text_color", "#FAF0EA"));
    }

    final String id, name;
    final Map<String, Object> style;

    private Look(String id, String name, Map<String, Object> style) {
        this.id = id;
        this.name = name;
        this.style = Collections.unmodifiableMap(style);
    }

    private static void builtin(String id, Map<String, Object> classic, Map<String, Object> changes) {
        builtin(id, id, classic, changes);
    }

    private static void builtin(String id, String name, Map<String, Object> classic, Map<String, Object> changes) {
        Map<String, Object> style = new LinkedHashMap<>(classic);
        style.putAll(changes);
        BUILTINS.put(id, new Look(id, name, style));
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
