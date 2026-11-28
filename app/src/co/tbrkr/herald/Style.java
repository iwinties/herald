package co.tbrkr.herald;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** An effective style. Every instance has all fields, in API document order. */
final class Style {
    static final String BUTTON_STYLES = "solid outline underline raised";
    static final Map<String, Object> BUTTON_DEFAULTS = Collections.unmodifiableMap(Json.fields(
            "button_shape", "rounded", "button_spacing", "normal", "button_style", "solid",
            "button_background", "#303A46", "button_text_color", "#DCE3EA",
            "button_focus_background", "#526579", "button_focus_text_color", "#F3F6F8"));
    final Map<String, Object> fields;

    private Style(Map<String, Object> fields) {
        this.fields = Collections.unmodifiableMap(fields);
    }

    static Style defaults(String icon, String accent, int duration, String stacking, String size, String night) {
        Map<String, Object> values = Json.fields("placement", "top_right", "size", size, "text_size", "medium",
                "accent", accent, "background", "#000000", "opacity", 90, "icon", icon, "duration", duration,
                "stacking", stacking, "sound", "none", "sound_volume", 60, "video_sound", "off",
                "show_here", true, "over_playback", "show", "night", night, "focus", "auto", "animation", "slide",
                "layout", "card", "corner", "small", "border", "none", "border_color", "#FFFFFF",
                "shadow", "none", "glow", "none", "accent_style", "bar", "title_color", "#FFFFFF",
                "text_color", "#FFFFFF", "title_weight", "bold", "label", "none");
        values.putAll(BUTTON_DEFAULTS);
        values.put("edge_margin",3);
        values.putAll(MotionPresets.DEFAULTS);
        return new Style(values);
    }

    static boolean validColor(Object value) {
        return value instanceof String && ((String) value).matches("#[A-Fa-f0-9]{6}");
    }

    static String icon(Object value, String field) {
        if (value instanceof String) {
            String s = (String) value;
            if (s.startsWith("image:") && CardSpec.key(s.substring(6))) return s;
            if (s.startsWith("emoji:")) {
                String text = s.substring(6);
                int length = text.codePointCount(0, text.length());
                if (length >= 1 && length <= 8 && text.codePoints().noneMatch(cp ->
                        Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp))) return s;
            }
        }
        return Settings.choice(value, field, "none info bell door camera alarm warning home media megaphone person package tv timer check");
    }

    Map<String, Object> sparse(Map<String, Object> patch, String path) {
        Map<String, Object> validated = new LinkedHashMap<>(merge(patch, path).fields);
        validated.keySet().retainAll(patch.keySet());
        return Collections.unmodifiableMap(validated);
    }

    Style merge(Map<String, Object> patch, String path) {
        Map<String, Object> result = new LinkedHashMap<>(fields);
        for (String key : fields.keySet()) {
            if (!patch.containsKey(key)) continue;
            Object v = patch.get(key);
            String field = path + "." + key;
            switch (key) {
                case "placement": v = Settings.choice(v, field, "top_left top top_right left center right bottom_left bottom bottom_right fullscreen"); break;
                case "size": v = Settings.choice(v, field, "small medium large full"); break;
                case "text_size": v = Settings.choice(v, field, "small medium large xlarge"); break;
                case "accent": case "background": case "border_color": case "title_color": case "text_color":
                case "button_background": case "button_text_color": case "button_focus_background": case "button_focus_text_color":
                    if (!validColor(v)) throw new Settings.Invalid(field);
                    break;
                case "opacity":
                    v = Settings.integer(v, field, 0, 100);
                    if ((int) v % 5 != 0) throw new Settings.Invalid(field);
                    break;
                case "icon": v = icon(v, field); break;
                case "edge_margin": v = Settings.integer(v, field, 0, 10); break;
                case "duration":
                    v = Settings.integer(v, field, 0, 3600);
                    if ((int) v == 1 || (int) v == 2) throw new Settings.Invalid(field);
                    break;
                case "stacking": v = Settings.choice(v, field, "replace queue stack"); break;
                case "sound":
                    if (!"none".equals(v) && !"chime".equals(v) && !(v instanceof String
                            && ((String) v).startsWith("clip:") && ClipStore.validKey(((String) v).substring(5)))) {
                        throw new Settings.Invalid(field);
                    }
                    break;
                case "sound_volume": v = Settings.integer(v, field, 0, 100); break;
                case "video_sound": v = Settings.choice(v, field, "on off"); break;
                case "show_here": if (!(v instanceof Boolean)) throw new Settings.Invalid(field); break;
                case "over_playback": v = Settings.choice(v, field, "show skip"); break;
                case "night": v = Settings.choice(v, field, "normal dim hide"); break;
                case "focus": v = Settings.choice(v, field, "auto never"); break;
                case "animation": v = Settings.choice(v, field, "slide fade none"); break;
                case "layout": v = Settings.choice(v, field, "card text strip pill"); break;
                case "corner": v = Settings.choice(v, field, "none small medium large pill"); break;
                case "border": v = Settings.choice(v, field, "none thin medium thick"); break;
                case "shadow": v = Settings.choice(v, field, "none soft strong"); break;
                case "glow": v = Settings.choice(v, field, "none accent border"); break;
                case "accent_style": v = Settings.choice(v, field, "bar badge dot underline none"); break;
                case "button_style": v = Settings.choice(v, field, BUTTON_STYLES); break;
                case "button_shape": v = Settings.choice(v, field, "square rounded pill"); break;
                case "button_spacing": v = Settings.choice(v, field, "compact normal roomy"); break;
                case "title_weight": v = Settings.choice(v, field, "bold normal"); break;
                case "label": v = Settings.choice(v, field, "none category"); break;
                default:
                    if (!MotionPresets.OPTIONS.containsKey(key)) throw new AssertionError(key);
                    v = Settings.choice(v, field, (String) MotionPresets.OPTIONS.get(key));
            }
            result.put(key, v);
        }
        if (patch.containsKey("animation") && !patch.containsKey("transition_in")) {
            Object old = patch.get("animation");
            result.put("transition_in", "slide".equals(old) ? "slide_auto" : old);
        }
        Settings.rejectUnknown(patch, fields, path);
        return new Style(result);
    }
}
