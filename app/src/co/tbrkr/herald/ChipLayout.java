package co.tbrkr.herald;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChipLayout {
    static final class Group {
        final List<Map<String, Object>> visible = new ArrayList<>();
        int hidden;
    }

    static Map<String, Group> groups(List<Map<String, Object>> rows, String defaultPosition) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String position = (String) row.get("position");
            Group group = groups.computeIfAbsent(position == null ? defaultPosition : position, k -> new Group());
            if (group.visible.size() < 5) group.visible.add(row);
            else group.hidden++;
        }
        return groups;
    }

    static int[] room(int width, int height) {
        return new int[]{(int) Math.ceil(.04 * width), (int) Math.ceil(.04 * height)};
    }

    static String glowColor(Map<String, Object> visual, String chipColor) {
        if ("border".equals(visual.get("glow"))) return (String) visual.get("border_color");
        return "accent".equals(visual.get("glow")) ? chipColor : null;
    }
}
