package io.github.iwinties.herald;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;

final class SettingsModel {
    static final List<String> SECTIONS = Collections.unmodifiableList(Arrays.asList("Looks", "Categories",
            "Display", "Sound & voice", "Pairing & clients", "MQTT", "Compatibility", "Status", "About"));

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

    static List<String> statusRows(Map<String,Object> status, List<String> clients, List<String> addresses) {
        List<String> rows = new ArrayList<>();
        rows.add(row("Version", status.get("version")));
        if (addresses == null || addresses.isEmpty()) rows.add(row("Address", null));
        else for (String address : addresses) rows.add(row("Address", address));
        rows.add(row("Paired clients", clients == null ? null : clients.size()
                + (clients.isEmpty() ? "" : " · " + String.join(", ", clients))));
        rows.add(row("Last card", status.get("last_card")));
        rows.add(row("Last audio", status.get("last_audio")));
        rows.add(row("Sound playing", status.get("sound_playing")));
        rows.add(row("Screen on", status.get("screen_on")));
        rows.add(row("MQTT", status.get("mqtt")));
        rows.add(row("Compatibility", status.get("compat")));
        rows.add(row("Overlay permission", status.get("overlay_allowed")));
        rows.add(row("Battery exemption", status.get("battery_exempt")));
        return rows;
    }

    private static String row(String label, Object value) {
        return label + "  " + (value == null ? "—" : value instanceof Map ? Json.stringify(value) : value);
    }
}
