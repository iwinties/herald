package co.tbrkr.herald;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;

final class SettingsModel {
    static final List<String> SECTIONS = Collections.unmodifiableList(Arrays.asList("Setup", "Pairing & clients", "Looks",
            "Categories", "Display", "Sound & voice", "Integrations", "Status", "About"));

    static final List<String> INTEGRATIONS = Collections.unmodifiableList(Arrays.asList("MQTT", "ntfy", "TvOverlay"));

    static String fieldLabel(String key) {
        switch (key) {
            case "max_visible": return "Maximum visible cards";
            case "night_start": return "Night starts";
            case "night_end": return "Night ends";
            case "pixel_shift": return "Pixel shift";
            case "sticky_cap": return "Maximum sticky duration";
            case "focus_while_playing": return "Allow button focus during playback";
            case "volume_tv": return "Announcement volume (TV)";
            case "volume_speaker": return "Announcement volume (box speaker)";
            case "allowed": return "Allow pairing";
            case "speaker_at_night": return "Speaker at night";
            case "chip_placement": return "Chip position";
            case "button_focus_text_color": return "Selected button text";
            case "button_focus_background": return "Selected button fill";
            case "Allowed senders (comma-separated)": return "Allowed senders";
            case "tls": return "TLS";
            default: return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1).replace('_', ' ');
        }
    }

    static String help(String key) {
        switch (key) {
            case "MQTT": return "Connect a broker for messages and device discovery.";
            case "ntfy": return "Show messages from subscribed topics.";
            case "TvOverlay": return "Receive cards from existing TvOverlay senders.";
            case "sticky_cap": return "Limits how long an untimed card can stay on screen.";
            case "pixel_shift": return "Moves persistent chips slightly to reduce burn-in.";
            case "focus_while_playing": return "Lets card buttons use the remote while media plays.";
            case "base_topic": return "Prefix shared by this TV’s MQTT topics.";
            case "Catch-up (seconds)": return "How far back to fetch messages after reconnecting.";
            case "Allowed senders (comma-separated)": return "Separate trusted IP addresses or network ranges with commas.";
            default: return null;
        }
    }

    static String fieldValue(String key, Object value) {
        if (value instanceof Boolean) return (boolean) value ? "On" : "Off";
        if (key.equals("speaker_at_night") && "urgent".equals(value)) return "Urgent only";
        if (key.equals("volume_tv") || key.equals("volume_speaker") || key.equals("opacity")) return value + "%";
        if (key.equals("duration") || key.equals("sticky_cap")) return value.equals(0) ? "Until cleared" : value + " s";
        if (key.equals("placement") || key.equals("chip_placement")) return fieldLabel(value.toString().replace('_', ' '));
        return "".equals(value) ? "Not set" : String.valueOf(value);
    }

    static Map<Look,List<String>> lookTiles(Settings settings) {
        synchronized (settings) {
            Map<Look,List<String>> tiles = new LinkedHashMap<>();
            for (Map<String,Object> row : settings.looks())
                tiles.put(settings.look((String)row.get("id")), new ArrayList<>());
            for (Map<String,Object> row : settings.categories())
                tiles.get(settings.look((String)row.get("look"))).add((String)row.get("name"));
            return tiles;
        }
    }

    static String newLookId(String name, Set<String> taken) {
        String stem = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        if (stem.isEmpty()) stem = "look";
        stem = stem.substring(0, Math.min(32, stem.length()));
        String candidate = stem;
        for (int n = 2; taken.contains(candidate); n++) {
            String suffix = "_" + n;
            candidate = stem.substring(0, Math.min(stem.length(), 32 - suffix.length())) + suffix;
        }
        return candidate;
    }

    static List<String> statusRows(Map<String,Object> status, List<String> clients, List<String> addresses, boolean details) {
        List<String> rows = new ArrayList<>();
        rows.add(row("Version", status.get("version")));
        if (addresses == null || addresses.isEmpty()) rows.add(row("Address", null));
        else for (String address : addresses) rows.add(row("Address", address));
        rows.add(row("API", status.get("api_status")));
        rows.add(row("Paired clients", clients == null ? null : clients.size()
                + (clients.isEmpty() ? "" : " · " + String.join(", ", clients))));
        rows.add(row("Last card", status.get("last_card")));
        rows.add(row("Last audio", status.get("last_audio")));
        if (!details) return rows;
        rows.add(row("Sound playing", status.get("sound_playing")));
        rows.add(row("Screen on", status.get("screen_on")));
        rows.add(row("MQTT", status.get("mqtt")));
        rows.add(row("ntfy", status.get("ntfy")));
        rows.add(row("Compatibility", status.get("compat")));
        rows.add(row("Overlay permission", status.get("overlay_allowed")));
        rows.add(row("Battery exemption", status.get("battery_exempt")));
        rows.add(row("Receiving", "On"));
        return rows;
    }

    private static String row(String label, Object value) {
        return label + "  " + (value == null ? "—" : value instanceof Map ? Json.stringify(value) : value);
    }
}
